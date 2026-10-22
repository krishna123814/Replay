package com.example.simplebrowser.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Crypto building blocks (sirf JDK/Android javax.crypto, koi external library nahi):
 *  scrypt, AES key wrap (RFC 3394), AES-SIV (RFC 5297), base64url, base32. */
final class Prims {
    private Prims() {}

    static byte[] utf8(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    static byte[] hmacSha256(byte[] key, byte[]... parts) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) m.update(p);
            return m.doFinal();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] sha1(byte[] d) {
        try { return MessageDigest.getInstance("SHA-1").digest(d); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static boolean constEq(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }

    // ───────────── base64url / base32 ─────────────
    private static final String B64U = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    static String b64url(byte[] d, boolean pad) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (; i + 2 < d.length; i += 3) {
            int v = (d[i] & 255) << 16 | (d[i + 1] & 255) << 8 | (d[i + 2] & 255);
            sb.append(B64U.charAt(v >> 18)).append(B64U.charAt(v >> 12 & 63))
              .append(B64U.charAt(v >> 6 & 63)).append(B64U.charAt(v & 63));
        }
        int rem = d.length - i;
        if (rem == 1) {
            int v = (d[i] & 255) << 16;
            sb.append(B64U.charAt(v >> 18)).append(B64U.charAt(v >> 12 & 63));
            if (pad) sb.append("==");
        } else if (rem == 2) {
            int v = (d[i] & 255) << 16 | (d[i + 1] & 255) << 8;
            sb.append(B64U.charAt(v >> 18)).append(B64U.charAt(v >> 12 & 63)).append(B64U.charAt(v >> 6 & 63));
            if (pad) sb.append('=');
        }
        return sb.toString();
    }

    /** Standard ya url-safe base64, padding ho ya na ho. */
    static byte[] unb64(String s) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        int acc = 0, bits = 0;
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '=') break;
            int v;
            if (c == '+') v = 62; else if (c == '/') v = 63; else v = B64U.indexOf(c);
            if (v < 0) throw new IllegalArgumentException("bad base64");
            acc = acc << 6 | v; bits += 6;
            if (bits >= 8) { bits -= 8; o.write(acc >> bits & 255); }
        }
        return o.toByteArray();
    }

    static String b64std(byte[] d) { return b64url(d, true).replace('-', '+').replace('_', '/'); }

    static String base32(byte[] d) {
        final String A = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        int acc = 0, bits = 0;
        for (byte b : d) {
            acc = acc << 8 | (b & 255); bits += 8;
            while (bits >= 5) { bits -= 5; sb.append(A.charAt(acc >> bits & 31)); }
        }
        if (bits > 0) sb.append(A.charAt(acc << (5 - bits) & 31));
        return sb.toString();
    }

    // ───────────── scrypt (RFC 7914) ─────────────
    static byte[] scrypt(byte[] pw, byte[] salt, int N, int r, int p, int dkLen) {
        byte[] b = pbkdf2One(pw, salt, p * 128 * r);
        int[] x = new int[32 * r], y = new int[32 * r], v = new int[32 * r * N];
        for (int i = 0; i < p; i++) {
            int off = i * 128 * r;
            for (int k = 0; k < 32 * r; k++) {
                int q = off + k * 4;
                x[k] = (b[q] & 255) | (b[q + 1] & 255) << 8 | (b[q + 2] & 255) << 16 | (b[q + 3] & 255) << 24;
            }
            for (int n = 0; n < N; n++) {
                System.arraycopy(x, 0, v, n * 32 * r, 32 * r);
                blockMix(x, y, r);
            }
            for (int n = 0; n < N; n++) {
                int j = x[(2 * r - 1) * 16] & (N - 1);
                int base = j * 32 * r;
                for (int k = 0; k < 32 * r; k++) x[k] ^= v[base + k];
                blockMix(x, y, r);
            }
            for (int k = 0; k < 32 * r; k++) {
                int q = off + k * 4;
                b[q] = (byte) x[k]; b[q + 1] = (byte) (x[k] >> 8); b[q + 2] = (byte) (x[k] >> 16); b[q + 3] = (byte) (x[k] >> 24);
            }
        }
        Arrays.fill(v, 0);
        return pbkdf2One(pw, b, dkLen);
    }

    /** PBKDF2-HMAC-SHA256, 1 iteration (scrypt ke liye kaafi). */
    private static byte[] pbkdf2One(byte[] pw, byte[] salt, int len) {
        byte[] out = new byte[len];
        int blocks = (len + 31) / 32;
        for (int i = 1; i <= blocks; i++) {
            byte[] ctr = {(byte) (i >> 24), (byte) (i >> 16), (byte) (i >> 8), (byte) i};
            byte[] t = hmacSha256(pw, salt, ctr);
            System.arraycopy(t, 0, out, (i - 1) * 32, Math.min(32, len - (i - 1) * 32));
        }
        return out;
    }

    private static void blockMix(int[] b, int[] y, int r) {
        int[] x = new int[16];
        System.arraycopy(b, (2 * r - 1) * 16, x, 0, 16);
        for (int i = 0; i < 2 * r; i++) {
            for (int k = 0; k < 16; k++) x[k] ^= b[i * 16 + k];
            salsa8(x);
            System.arraycopy(x, 0, y, i * 16, 16);
        }
        for (int i = 0; i < r; i++) {
            System.arraycopy(y, (2 * i) * 16, b, i * 16, 16);
            System.arraycopy(y, (2 * i + 1) * 16, b, (i + r) * 16, 16);
        }
    }

    private static int rl(int a, int b) { return a << b | a >>> (32 - b); }

    private static void salsa8(int[] b) {
        int[] x = b.clone();
        for (int i = 0; i < 8; i += 2) {
            x[4] ^= rl(x[0] + x[12], 7);  x[8] ^= rl(x[4] + x[0], 9);
            x[12] ^= rl(x[8] + x[4], 13); x[0] ^= rl(x[12] + x[8], 18);
            x[9] ^= rl(x[5] + x[1], 7);   x[13] ^= rl(x[9] + x[5], 9);
            x[1] ^= rl(x[13] + x[9], 13); x[5] ^= rl(x[1] + x[13], 18);
            x[14] ^= rl(x[10] + x[6], 7); x[2] ^= rl(x[14] + x[10], 9);
            x[6] ^= rl(x[2] + x[14], 13); x[10] ^= rl(x[6] + x[2], 18);
            x[3] ^= rl(x[15] + x[11], 7); x[7] ^= rl(x[3] + x[15], 9);
            x[11] ^= rl(x[7] + x[3], 13); x[15] ^= rl(x[11] + x[7], 18);
            x[1] ^= rl(x[0] + x[3], 7);   x[2] ^= rl(x[1] + x[0], 9);
            x[3] ^= rl(x[2] + x[1], 13);  x[0] ^= rl(x[3] + x[2], 18);
            x[6] ^= rl(x[5] + x[4], 7);   x[7] ^= rl(x[6] + x[5], 9);
            x[4] ^= rl(x[7] + x[6], 13);  x[5] ^= rl(x[4] + x[7], 18);
            x[11] ^= rl(x[10] + x[9], 7); x[8] ^= rl(x[11] + x[10], 9);
            x[9] ^= rl(x[8] + x[11], 13); x[10] ^= rl(x[9] + x[8], 18);
            x[12] ^= rl(x[15] + x[14], 7); x[13] ^= rl(x[12] + x[15], 9);
            x[14] ^= rl(x[13] + x[12], 13); x[15] ^= rl(x[14] + x[13], 18);
        }
        for (int i = 0; i < 16; i++) b[i] += x[i];
    }

    // ───────────── AES key wrap (RFC 3394) ─────────────
    private static Cipher ecb(byte[] key, boolean enc) {
        try {
            Cipher c = Cipher.getInstance("AES/ECB/NoPadding");
            c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return c;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] keyWrap(byte[] kek, byte[] plain) {
        try {
            int n = plain.length / 8;
            byte[] a = new byte[8]; Arrays.fill(a, (byte) 0xA6);
            byte[][] r = new byte[n][8];
            for (int i = 0; i < n; i++) System.arraycopy(plain, i * 8, r[i], 0, 8);
            Cipher c = ecb(kek, true);
            for (int j = 0; j < 6; j++) for (int i = 0; i < n; i++) {
                byte[] in = new byte[16];
                System.arraycopy(a, 0, in, 0, 8); System.arraycopy(r[i], 0, in, 8, 8);
                byte[] bb = c.doFinal(in);
                long t = (long) n * j + i + 1;
                System.arraycopy(bb, 0, a, 0, 8);
                for (int k = 0; k < 8; k++) a[7 - k] ^= (byte) (t >> (8 * k));
                System.arraycopy(bb, 8, r[i], 0, 8);
            }
            byte[] out = new byte[(n + 1) * 8];
            System.arraycopy(a, 0, out, 0, 8);
            for (int i = 0; i < n; i++) System.arraycopy(r[i], 0, out, (i + 1) * 8, 8);
            return out;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** null = integrity check fail (galat password). */
    static byte[] keyUnwrap(byte[] kek, byte[] wrapped) {
        try {
            int n = wrapped.length / 8 - 1;
            byte[] a = new byte[8];
            System.arraycopy(wrapped, 0, a, 0, 8);
            byte[][] r = new byte[n][8];
            for (int i = 0; i < n; i++) System.arraycopy(wrapped, (i + 1) * 8, r[i], 0, 8);
            Cipher c = ecb(kek, false);
            for (int j = 5; j >= 0; j--) for (int i = n - 1; i >= 0; i--) {
                long t = (long) n * j + i + 1;
                byte[] in = new byte[16];
                System.arraycopy(a, 0, in, 0, 8);
                for (int k = 0; k < 8; k++) in[7 - k] ^= (byte) (t >> (8 * k));
                System.arraycopy(r[i], 0, in, 8, 8);
                byte[] bb = c.doFinal(in);
                System.arraycopy(bb, 0, a, 0, 8);
                System.arraycopy(bb, 8, r[i], 0, 8);
            }
            for (byte x : a) if (x != (byte) 0xA6) return null;
            byte[] out = new byte[n * 8];
            for (int i = 0; i < n; i++) System.arraycopy(r[i], 0, out, i * 8, 8);
            return out;
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    // ───────────── AES-SIV (RFC 5297) ─────────────
    /** K1 = macKey (CMAC / S2V), K2 = encKey (CTR). Cryptomator ka filename / dir-id encryption. */
    static final class Siv {
        private final byte[] encKey;
        private final Cipher mac;
        private final byte[] k1, k2;

        Siv(byte[] encKey, byte[] macKey) {
            this.encKey = encKey.clone();
            this.mac = ecb(macKey, true);
            try {
                byte[] l = mac.doFinal(new byte[16]);
                k1 = dbl(l); k2 = dbl(k1);
            } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        }

        private static byte[] dbl(byte[] b) {
            byte[] o = new byte[16];
            for (int i = 0; i < 15; i++) o[i] = (byte) (b[i] << 1 | (b[i + 1] & 255) >>> 7);
            o[15] = (byte) (b[15] << 1);
            if ((b[0] & 0x80) != 0) o[15] ^= 0x87;
            return o;
        }

        private static byte[] xor(byte[] a, byte[] b) {
            byte[] o = new byte[16];
            for (int i = 0; i < 16; i++) o[i] = (byte) (a[i] ^ b[i]);
            return o;
        }

        private byte[] cmac(byte[] m) throws GeneralSecurityException {
            int n = Math.max(1, (m.length + 15) / 16);
            boolean full = m.length > 0 && m.length % 16 == 0;
            byte[] x = new byte[16];
            for (int i = 0; i < n - 1; i++) x = mac.doFinal(xor(x, Arrays.copyOfRange(m, i * 16, i * 16 + 16)));
            byte[] last = new byte[16];
            int rem = m.length - (n - 1) * 16;
            System.arraycopy(m, (n - 1) * 16, last, 0, rem);
            if (full) last = xor(last, k1);
            else { last[rem] = (byte) 0x80; last = xor(last, k2); }
            return mac.doFinal(xor(x, last));
        }

        private byte[] s2v(byte[] ad, byte[] p) throws GeneralSecurityException {
            byte[] d = cmac(new byte[16]);
            if (ad != null) d = xor(dbl(d), cmac(ad));
            byte[] t;
            if (p.length >= 16) {
                t = p.clone();
                int o = p.length - 16;
                for (int i = 0; i < 16; i++) t[o + i] ^= d[i];
            } else {
                byte[] pad = new byte[16];
                System.arraycopy(p, 0, pad, 0, p.length);
                pad[p.length] = (byte) 0x80;
                t = xor(dbl(d), pad);
            }
            return cmac(t);
        }

        private byte[] ctr(byte[] v, byte[] data) throws GeneralSecurityException {
            byte[] q = v.clone();
            q[8] &= 0x7f; q[12] &= 0x7f;
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encKey, "AES"), new IvParameterSpec(q));
            return c.doFinal(data);
        }

        /** ad = null ho sakta hai (dir id), warna ek associated-data string (parent dir id). */
        byte[] encrypt(byte[] plain, byte[] ad) {
            try {
                byte[] v = s2v(ad, plain);
                byte[] c = ctr(v, plain);
                byte[] out = new byte[16 + c.length];
                System.arraycopy(v, 0, out, 0, 16);
                System.arraycopy(c, 0, out, 16, c.length);
                return out;
            } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        }

        /** null = authentication fail. */
        byte[] decrypt(byte[] in, byte[] ad) {
            try {
                if (in.length < 16) return null;
                byte[] v = Arrays.copyOfRange(in, 0, 16);
                byte[] p = ctr(v, Arrays.copyOfRange(in, 16, in.length));
                return constEq(s2v(ad, p), v) ? p : null;
            } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
        }

        void wipe() { Arrays.fill(encKey, (byte) 0); }
    }
}
