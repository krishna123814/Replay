package com.example.simplebrowser.vault;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cryptomator-compatible vault (format 8, cipherCombo SIV_GCM, Cryptomator 1.7+ ka default).
 * Is folder ko laptop par Cryptomator (Windows / Linux / Mac) se password dalke khol sakte ho.
 * Sab kuch java.io.File par: SD card ke andar koi bhi folder vault ban sakta hai.
 */
public final class CryptoVault {

    public static class WrongPasswordException extends Exception {
        private static final long serialVersionUID = 1L;
        public WrongPasswordException() { super("Galat password"); }
    }
    public static class VaultFormatException extends Exception {
        private static final long serialVersionUID = 1L;
        public VaultFormatException(String m) { super(m); }
    }

    /** Ek decrypted entry (file ya folder). */
    public static final class Item {
        public final String name;
        public final boolean isDir;
        public final long size;
        public final long modified;
        /** Folder ho to uska dir id (list() me use karo), file ho to null. */
        public final String dirId;
        Item(String name, boolean isDir, long size, long modified, String dirId) {
            this.name = name; this.isDir = isDir; this.size = size; this.modified = modified; this.dirId = dirId;
        }
    }

    public static final String ROOT = "";
    private static final int VERSION = 999;
    private static final int FORMAT = 8;
    private static final int SHORTEN = 220;
    private static final int CHUNK = 32 * 1024;
    private static final int HDR = 68;                 // nonce 12 + payload 40 + tag 16
    private static final int GCM_OVERHEAD = 28;        // nonce 12 + tag 16
    private static final SecureRandom RNG = new SecureRandom();

    private final File root;
    private final byte[] encKey, macKey;
    private final Prims.Siv siv;

    private CryptoVault(File root, byte[] encKey, byte[] macKey) {
        this.root = root; this.encKey = encKey; this.macKey = macKey;
        this.siv = new Prims.Siv(encKey, macKey);
    }

    // ───────────────────────── create / open ─────────────────────────

    public static boolean isVault(File dir) {
        return new File(dir, "vault.cryptomator").isFile() && new File(dir, "masterkey.cryptomator").isFile();
    }

    public static CryptoVault create(File dir, String password) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Folder ban nahi paya");
        if (isVault(dir)) throw new IOException("Is folder me vault pehle se hai");
        byte[] enc = new byte[32], mac = new byte[32];
        RNG.nextBytes(enc); RNG.nextBytes(mac);
        String mk = masterJson(enc, mac, password);

        String head = Prims.b64url(Prims.utf8("{\"kid\":\"masterkeyfile:masterkey.cryptomator\",\"typ\":\"JWT\",\"alg\":\"HS256\"}"), false);
        String body = Prims.b64url(Prims.utf8("{\"jti\":\"" + UUID.randomUUID() + "\",\"format\":" + FORMAT
                + ",\"cipherCombo\":\"SIV_GCM\",\"shorteningThreshold\":" + SHORTEN + "}"), false);
        byte[] raw = concat(enc, mac);
        String jwt = head + "." + body + "." + Prims.b64url(Prims.hmacSha256(raw, Prims.utf8(head + "." + body)), false);
        Arrays.fill(raw, (byte) 0);

        writeText(new File(dir, "masterkey.cryptomator"), mk);
        writeText(new File(dir, "vault.cryptomator"), jwt);
        CryptoVault v = new CryptoVault(dir, enc, mac);
        if (!v.dirPath(ROOT).isDirectory() && !v.dirPath(ROOT).mkdirs()) throw new IOException("Root folder nahi bana");
        return v;
    }

    public static CryptoVault open(File dir, String password) throws IOException, WrongPasswordException, VaultFormatException {
        String mk = readText(new File(dir, "masterkey.cryptomator"));
        String cfg = readText(new File(dir, "vault.cryptomator")).trim();
        try {
            byte[] salt = Prims.unb64(str(mk, "scryptSalt"));
            int cost = (int) num(mk, "scryptCostParam"), block = (int) num(mk, "scryptBlockSize");
            int ver = (int) num(mk, "version");
            if (ver != VERSION) throw new VaultFormatException("Masterkey version " + ver + " support nahi hai");
            if (cost < 2 || (cost & (cost - 1)) != 0 || cost > (1 << 20) || block < 1 || block > 32)
                throw new VaultFormatException("Masterkey scrypt parameter galat hain");
            byte[] kek = Prims.scrypt(pwBytes(password), salt, cost, block, 1, 32);
            byte[] enc = Prims.keyUnwrap(kek, Prims.unb64(str(mk, "primaryMasterKey")));
            byte[] mac = Prims.keyUnwrap(kek, Prims.unb64(str(mk, "hmacMasterKey")));
            Arrays.fill(kek, (byte) 0);
            if (enc == null || mac == null) throw new WrongPasswordException();
            byte[] vm = Prims.hmacSha256(mac, new byte[]{0, 0, (byte) (ver >> 8), (byte) ver});
            if (!Prims.constEq(vm, Prims.unb64(str(mk, "versionMac")))) throw new WrongPasswordException();

            String[] parts = cfg.split("\\.");
            if (parts.length != 3) throw new VaultFormatException("vault.cryptomator galat hai");
            byte[] sig = Prims.hmacSha256(concat(enc, mac), Prims.utf8(parts[0] + "." + parts[1]));
            if (!Prims.constEq(sig, Prims.unb64(parts[2]))) throw new VaultFormatException("vault.cryptomator ka signature match nahi hua");
            String payload = new String(Prims.unb64(parts[1]), StandardCharsets.UTF_8);
            if (num(payload, "format") != FORMAT) throw new VaultFormatException("Vault format " + (int) num(payload, "format") + " support nahi (sirf format 8)");
            if (!"SIV_GCM".equals(str(payload, "cipherCombo")))
                throw new VaultFormatException("Ye vault " + str(payload, "cipherCombo") + " use karta hai. Sirf SIV_GCM (Cryptomator 1.7+) support hai");
            if (num(payload, "shorteningThreshold") != SHORTEN) throw new VaultFormatException("shorteningThreshold alag hai");
            return new CryptoVault(dir, enc, mac);
        } catch (IllegalArgumentException e) {
            throw new VaultFormatException("Vault file kharab hai");
        }
    }

    public void close() {
        Arrays.fill(encKey, (byte) 0);
        Arrays.fill(macKey, (byte) 0);
        siv.wipe();
    }


    private static String masterJson(byte[] enc, byte[] mac, String password) {
        byte[] salt = new byte[8];
        RNG.nextBytes(salt);
        final int cost = 32768, block = 8;
        byte[] kek = Prims.scrypt(pwBytes(password), salt, cost, block, 1, 32);
        byte[] vm = Prims.hmacSha256(mac, new byte[]{0, 0, (byte) (VERSION >> 8), (byte) VERSION});
        String mk = "{\n  \"version\": " + VERSION + ",\n"
                + "  \"scryptSalt\": \"" + Prims.b64std(salt) + "\",\n"
                + "  \"scryptCostParam\": " + cost + ",\n"
                + "  \"scryptBlockSize\": " + block + ",\n"
                + "  \"primaryMasterKey\": \"" + Prims.b64std(Prims.keyWrap(kek, enc)) + "\",\n"
                + "  \"hmacMasterKey\": \"" + Prims.b64std(Prims.keyWrap(kek, mac)) + "\",\n"
                + "  \"versionMac\": \"" + Prims.b64std(vm) + "\"\n}\n";
        Arrays.fill(kek, (byte) 0);
        return mk;
    }

    /**
     * Password badlo. Files dobara encrypt nahi hotin: sirf masterkey.cryptomator naye password se lock hoti hai.
     * Pehle naya masterkey tmp me likhta hai, phir purane ko badalta hai.
     */
    public void changePassword(String newPassword) throws IOException {
        File mkFile = new File(root, "masterkey.cryptomator");
        File tmp = new File(root, "masterkey.cryptomator.tmp");
        writeText(tmp, masterJson(encKey, macKey, newPassword));
        if (!tmp.renameTo(mkFile)) {
            if (mkFile.exists() && !mkFile.delete()) { tmp.delete(); throw new IOException("Purani masterkey file badal nahi payi"); }
            if (!tmp.renameTo(mkFile)) throw new IOException("Naya password save nahi hua (tmp file: " + tmp.getName() + ")");
        }
    }

    // ───────────────────────── naming / paths ─────────────────────────

    private File dirPath(String dirId) {
        String h = Prims.base32(Prims.sha1(siv.encrypt(Prims.utf8(dirId), null)));
        return new File(new File(new File(root, "d"), h.substring(0, 2)), h.substring(2));
    }

    private static String normName(String n) { return Normalizer.normalize(n, Normalizer.Form.NFC); }

    private String encName(String parentId, String name) {
        return Prims.b64url(siv.encrypt(Prims.utf8(normName(name)), Prims.utf8(parentId)), true) + ".c9r";
    }

    private String decName(String parentId, String c9r) {
        byte[] p = siv.decrypt(Prims.unb64(c9r.substring(0, c9r.length() - 4)), Prims.utf8(parentId));
        return p == null ? null : new String(p, StandardCharsets.UTF_8);
    }

    /** Entry ka container: chhota naam = dp/<enc>.c9r; lamba = dp/<sha1>.c9s/ */
    private File base(String parentId, String name) {
        String c9r = encName(parentId, name);
        File dp = dirPath(parentId);
        if (c9r.length() > SHORTEN) return new File(dp, Prims.b64url(Prims.sha1(Prims.utf8(c9r)), true) + ".c9s");
        return new File(dp, c9r);
    }

    private boolean isShort(File base) { return base.getName().endsWith(".c9s"); }

    private File fileNode(File base) { return isShort(base) ? new File(base, "contents.c9r") : base; }

    private void prepShort(File base, String parentId, String name) throws IOException {
        if (!isShort(base)) return;
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Folder nahi bana");
        writeText(new File(base, "name.c9s"), encName(parentId, name));
    }

    // ───────────────────────── operations ─────────────────────────

    public List<Item> list(String dirId) throws IOException {
        File dp = dirPath(dirId);
        File[] kids = dp.listFiles();
        List<Item> out = new ArrayList<>();
        if (kids == null) return out;
        for (File f : kids) {
            String fn = f.getName();
            String c9r; File node;
            if (fn.endsWith(".c9r")) { c9r = fn; node = f; }
            else if (fn.endsWith(".c9s") && f.isDirectory()) {
                File nm = new File(f, "name.c9s");
                if (!nm.isFile()) continue;
                c9r = readText(nm).trim();
                if (!c9r.endsWith(".c9r")) continue;
                node = f;
            } else continue;
            try {
                String name = decName(dirId, c9r);
                if (name == null) continue;
                File dirMark = new File(node, "dir.c9r");
                if (node.isDirectory() && dirMark.isFile()) {
                    out.add(new Item(name, true, 0, f.lastModified(), readText(dirMark).trim()));
                } else {
                    File data = isShort(f) ? new File(f, "contents.c9r") : f;
                    if (data.isFile()) out.add(new Item(name, false, cleartextSize(data.length()), data.lastModified(), null));
                }
            } catch (IllegalArgumentException e) { /* naam decode nahi hua: chhod do */ }
        }
        Collections.sort(out, (a, b) -> a.isDir != b.isDir ? (a.isDir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
        return out;
    }

    public Item find(String dirId, String name) throws IOException {
        for (Item i : list(dirId)) if (i.name.equals(normName(name))) return i;
        return null;
    }

    /** Naya folder; uska dir id lautata hai. */
    public String mkdir(String parentId, String name) throws IOException {
        File b = base(parentId, name);
        if (b.exists()) throw new IOException("\"" + name + "\" pehle se hai");
        String id = UUID.randomUUID().toString();
        prepShort(b, parentId, name);
        if (!b.isDirectory() && !b.mkdirs()) throw new IOException("Folder nahi bana");
        writeText(new File(b, "dir.c9r"), id);
        File dp = dirPath(id);
        if (!dp.isDirectory() && !dp.mkdirs()) throw new IOException("Folder nahi bana");
        return id;
    }

    /** `in` se padhkar encrypt karke vault me file banata hai (pehle se ho to badal deta hai). */
    public void write(String parentId, String name, InputStream in) throws IOException {
        File b = base(parentId, name);
        File node = fileNode(b);
        if (b.exists() && b.isDirectory() && new File(b, "dir.c9r").isFile())
            throw new IOException("\"" + name + "\" naam ka folder hai");
        prepShort(b, parentId, name);
        File tmp = new File(node.getParentFile(), node.getName() + ".tmp");
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16)) {
            encryptStream(in, o);
        } catch (IOException | RuntimeException e) {
            tmp.delete();
            throw e;
        }
        if (node.exists() && !node.delete()) { tmp.delete(); throw new IOException("Purani file hata nahi paya"); }
        if (!tmp.renameTo(node)) { tmp.delete(); throw new IOException("File save nahi hui"); }
    }

    /** Vault ki file decrypt karke `out` me likhta hai. Tamper / corrupt ho to IOException. */
    public void read(String parentId, String name, OutputStream out) throws IOException {
        File node = fileNode(base(parentId, name));
        if (!node.isFile()) throw new FileNotFoundException(name);
        try (InputStream in = new BufferedInputStream(new FileInputStream(node), 1 << 16)) {
            decryptStream(in, out);
        }
    }

    public void delete(String parentId, String name) throws IOException {
        File b = base(parentId, name);
        if (!b.exists()) return;
        File mark = new File(b, "dir.c9r");
        if (b.isDirectory() && mark.isFile()) wipeDir(readText(mark).trim());
        deleteTree(b);
    }

    public void rename(String parentId, String oldName, String newName) throws IOException {
        File ob = base(parentId, oldName), nb = base(parentId, newName);
        if (!ob.exists()) throw new FileNotFoundException(oldName);
        if (nb.exists()) throw new IOException("\"" + newName + "\" pehle se hai");
        boolean dir = ob.isDirectory() && new File(ob, "dir.c9r").isFile();
        if (isShort(ob) == isShort(nb)) {
            if (!ob.renameTo(nb)) throw new IOException("Naam nahi badla");
            if (isShort(nb)) writeText(new File(nb, "name.c9s"), encName(parentId, newName));
            return;
        }
        // chhota <-> lamba naam: container badalta hai, andar ka content wahi rehta hai
        prepShort(nb, parentId, newName);
        File srcNode = dir ? new File(ob, "dir.c9r") : fileNode(ob);
        File dstNode = dir ? new File(nb, "dir.c9r") : fileNode(nb);
        if (!nb.isDirectory() && !isShort(nb)) { /* chhota naam: nb khud node hai */ }
        if (dir) {
            if (!nb.isDirectory() && !nb.mkdirs()) throw new IOException("Naam nahi badla");
            if (!srcNode.renameTo(dstNode)) throw new IOException("Naam nahi badla");
        } else if (!srcNode.renameTo(dstNode)) throw new IOException("Naam nahi badla");
        deleteTree(ob);
    }

    private void wipeDir(String dirId) throws IOException {
        for (Item i : list(dirId)) if (i.isDir) wipeDir(i.dirId);
        File dp = dirPath(dirId);
        deleteTree(dp);
        File shard = dp.getParentFile();
        String[] left = shard == null ? null : shard.list();
        if (left != null && left.length == 0) shard.delete();
    }

    private static void deleteTree(File f) throws IOException {
        File[] k = f.isDirectory() ? f.listFiles() : null;
        if (k != null) for (File c : k) deleteTree(c);
        if (f.exists() && !f.delete()) throw new IOException("Hata nahi paya: " + f.getName());
    }

    // ───────────────────────── file content (SIV_GCM) ─────────────────────────

    public static long cleartextSize(long encSize) {
        if (encSize < HDR) return 0;
        long body = encSize - HDR;
        long full = body / (CHUNK + GCM_OVERHEAD), rest = body % (CHUNK + GCM_OVERHEAD);
        return full * CHUNK + (rest > GCM_OVERHEAD ? rest - GCM_OVERHEAD : 0);
    }

    private static Cipher gcm(boolean enc, byte[] key, byte[] nonce, byte[]... aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        for (byte[] a : aad) c.updateAAD(a);
        return c;
    }

    private static byte[] be64(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) { b[i] = (byte) v; v >>>= 8; }
        return b;
    }

    private void encryptStream(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] hNonce = new byte[12], cKey = new byte[32];
            RNG.nextBytes(hNonce); RNG.nextBytes(cKey);
            byte[] payload = new byte[40];
            Arrays.fill(payload, 0, 8, (byte) 0xFF);
            System.arraycopy(cKey, 0, payload, 8, 32);
            out.write(hNonce);
            out.write(gcm(true, encKey, hNonce).doFinal(payload));
            byte[] buf = new byte[CHUNK];
            long n = 0;
            while (true) {
                int got = readFully(in, buf);
                if (got <= 0) break;
                byte[] nonce = new byte[12];
                RNG.nextBytes(nonce);
                out.write(nonce);
                out.write(gcm(true, cKey, nonce, be64(n), hNonce).doFinal(buf, 0, got));
                n++;
                if (got < CHUNK) break;
            }
            Arrays.fill(cKey, (byte) 0);
        } catch (GeneralSecurityException e) { throw new IOException(e); }
    }

    private void decryptStream(InputStream in, OutputStream out) throws IOException {
        try {
            byte[] hdr = new byte[HDR];
            if (readFully(in, hdr) != HDR) throw new IOException("File header kharab hai");
            byte[] hNonce = Arrays.copyOfRange(hdr, 0, 12);
            byte[] payload;
            try { payload = gcm(false, encKey, hNonce).doFinal(hdr, 12, HDR - 12); }
            catch (GeneralSecurityException e) { throw new IOException("File header authenticate nahi hua (kharab / badli hui file)"); }
            byte[] cKey = Arrays.copyOfRange(payload, 8, 40);
            byte[] buf = new byte[CHUNK + GCM_OVERHEAD];
            long n = 0;
            while (true) {
                int got = readFully(in, buf);
                if (got <= 0) break;
                if (got <= GCM_OVERHEAD) throw new IOException("Chunk kharab hai");
                byte[] nonce = Arrays.copyOfRange(buf, 0, 12);
                byte[] plain;
                try { plain = gcm(false, cKey, nonce, be64(n), hNonce).doFinal(buf, 12, got - 12); }
                catch (GeneralSecurityException e) { throw new IOException("Chunk " + n + " authenticate nahi hua (kharab / badli hui file)"); }
                out.write(plain);
                n++;
                if (got < buf.length) break;
            }
            Arrays.fill(cKey, (byte) 0);
        } catch (IllegalStateException e) { throw new IOException(e); }
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int t = 0;
        while (t < b.length) {
            int r = in.read(b, t, b.length - t);
            if (r < 0) break;
            t += r;
        }
        return t;
    }


    // ───────────────────────── random access (video / audio streaming) ─────────────────────────

    /** Vault ki file ko seek karke padhne ke liye (player ke liye): sirf zaroori chunk decrypt hota hai. */
    public Reader openReader(String parentId, String name) throws IOException {
        File node = fileNode(base(parentId, name));
        if (!node.isFile()) throw new FileNotFoundException(name);
        return new Reader(node);
    }

    public final class Reader implements Closeable {
        private final RandomAccessFile raf;
        private final byte[] cKey, hNonce;
        private final long encLen, size;
        private long cachedIdx = -1;
        private byte[] cached;

        Reader(File f) throws IOException {
            raf = new RandomAccessFile(f, "r");
            try {
                encLen = raf.length();
                byte[] hdr = new byte[HDR];
                raf.readFully(hdr);
                hNonce = Arrays.copyOfRange(hdr, 0, 12);
                byte[] payload;
                try { payload = gcm(false, encKey, hNonce).doFinal(hdr, 12, HDR - 12); }
                catch (GeneralSecurityException e) { throw new IOException("File header authenticate nahi hua"); }
                cKey = Arrays.copyOfRange(payload, 8, 40);
                size = cleartextSize(encLen);
            } catch (IOException | RuntimeException e) {
                raf.close();
                throw e;
            }
        }

        public long length() { return size; }

        /** -1 = file khatam. Kam bytes bhi mil sakte hain (chunk ki seema par). */
        public synchronized int read(long pos, byte[] b, int off, int len) throws IOException {
            if (pos >= size || len == 0) return pos >= size ? -1 : 0;
            int total = 0;
            while (total < len && pos < size) {
                long idx = pos / CHUNK;
                load(idx);
                int inOff = (int) (pos % CHUNK);
                int n = Math.min(len - total, cached.length - inOff);
                if (n <= 0) break;
                System.arraycopy(cached, inOff, b, off + total, n);
                pos += n; total += n;
            }
            return total;
        }

        private void load(long idx) throws IOException {
            if (idx == cachedIdx) return;
            long off = HDR + idx * (CHUNK + GCM_OVERHEAD);
            int n = (int) Math.min(CHUNK + GCM_OVERHEAD, encLen - off);
            if (n <= GCM_OVERHEAD) throw new IOException("Chunk kharab hai");
            byte[] buf = new byte[n];
            raf.seek(off);
            raf.readFully(buf);
            try {
                cached = gcm(false, cKey, Arrays.copyOfRange(buf, 0, 12), be64(idx), hNonce).doFinal(buf, 12, n - 12);
                cachedIdx = idx;
            } catch (GeneralSecurityException e) {
                throw new IOException("Chunk " + idx + " authenticate nahi hua (kharab / badli hui file)");
            }
        }

        @Override public synchronized void close() throws IOException {
            Arrays.fill(cKey, (byte) 0);
            raf.close();
        }
    }

    // ───────────────────────── small helpers ─────────────────────────

    private static byte[] pwBytes(String p) { return Prims.utf8(Normalizer.normalize(p, Normalizer.Form.NFC)); }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static void writeText(File f, String s) throws IOException {
        try (OutputStream o = new FileOutputStream(f)) { o.write(Prims.utf8(s)); }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int r;
            while ((r = in.read(b)) > 0) o.write(b, 0, r);
            return new String(o.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing " + key);
        return m.group(1);
    }

    private static long num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing " + key);
        return Long.parseLong(m.group(1));
    }
}
