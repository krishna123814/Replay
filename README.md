# Lion (Android)

Ek app, do mode:
1. **Normal Browser** – full-screen WebView browser (URL bar, back/forward/reload, search), status bar hidden (immersive), file upload.
2. **Floating Window** – kisi bhi app ke upar tairta golden 🦁 dot + browser window.

## Floating dot + pill menu
- Startup par sirf chhota golden dot dikhta hai (default 16dp, settings me 10–28dp).
- Dot tap -> browser window minimize/open (toggle). Dot long-press (dabaye rakho) -> pill menu: ⟳ refresh, ⛶ maximize, 🪟 window show/hide, ⚙ settings, ✕ close. Dot ko drag karke move/❌ zone me band.
  4 sec koi action na ho to pill apne aap band.
- **Gestures** (Settings me on/off toggle, default on): floating browser ke **left kinare (18dp, invisible zone) se andar swipe = Back**, **right kinare se andar swipe = Forward** (80dp se zyada khincho, haptic + arrow dikhta hai). Pull-to-refresh gesture hata diya gaya h (chart touch lag ke karan); refresh Settings ke button se hota h. Gestures off karoge to pill me ◀ back button wapas aa jata hai.
- Dot ko kahin bhi drag karo (position yaad rehti hai). Drag karte waqt neeche ❌ zone me chhodo to band.
- Browser window: upar ke patle handle se drag, ◢ corner se resize, ⛶ se maximize (status bar tak poori screen).
- ⚙ settings: website URL, icon size, dot opacity, dot size.
- File upload (input type=file), JS prompt, fullscreen video chalte hain.
- Saare http/https links (google search, login, naye window/tab) isi floating window me khulte hain; sirf tel:/mailto: jaise links dusri app me jate hain.

## Multiple independent floating windows
- Pill menu me **➕**: URL poochta hai (pehle se bhara hua), phir bilkul nayi independent floating window khulti hai (apna dot, apna browser, apna size/position).
- Max 3 windows. Dot ka rang alag (sone-jaisa, neela, hara); 2+ windows hone par dot par number (1, 2, 3) dikhta hai.
- Har window ka apna URL, dot position aur browser size/position yaad rehta hai; app dobara chalne par wahi windows wapas aati hain.
- **✕** ya dot ko ❌ zone me chhodna sirf usi window ko band karta hai; aakhri window band hote hi service band. Notification ka "Band karo" sab band karta hai.
- **▤ Layout button** (pill me): 2+ windows hon to dabane par sab windows ek saath screen par barabar hisson me aa jati hain. 1st tap = upar-neeche (2 ya 3 barabar patti), 2nd tap = side-by-side, 3rd tap = pehle jaisa size/position. Layout chalu ho aur window ➕ ya ✕ ho to baaki apne aap dobara barabar ho jati hain.
- Cookies/login sab windows me shared; icon size, opacity aur dot size ki settings bhi sab windows ke liye same.

## Bookmarks
- ⚙ settings me "Bookmarks": **⭐ Current page bookmark karo** se page save hota hai (title ke saath, max 40). List me kisi bookmark par tap -> usi window me khulta hai (aur us window ka URL ban jata hai). Row ke **✕** se hatao (confirm poochta hai).
- ➕ nayi window ke dialog me **Bookmarks** button: list se chuno to us bookmark ki nayi independent window khulti hai.
- Bookmarks sab windows me shared hain. Pehli baar default site pehle se bookmark hoti hai.

## Downloader (abhi + schedule)
- Kisi bhi page par file download link dabao (floating window ya normal browser): dialog aata hai -> **Abhi** (turant download), **Schedule…** ya Cancel. "Sirf Wi-Fi par" checkbox bhi hai.
- Schedule options: 15 minute / 1 ghante / 3 ghante baad, "Raat 2:00 baje", ya apna time chuno (waqt nikal chuka ho to agle din ka).
- ⚙ settings -> **⬇ Downloads / Schedule**: scheduled downloads ki list (tap karke hatao), **Naya URL** (link paste karke download/schedule), **Downloads dekho** (system downloads list: pause/resume/open).
- Download Android ke DownloadManager se hota hai (notification, resume). File `Downloads/Lion/` me aati hai (Android 9 ya kam par app ke apne Downloads folder me). Login wali site ke liye cookie aur referer saath jate hain.
- Schedule exact-minute nahi hota: phone Doze me ho to kuch minute upar-neeche ho sakta hai. Reboot ya app update ke baad schedule wapas set ho jata hai.

## ⚡ Fast download engine (v3.1)
Sab kuch ⚙ settings ke andar hai, main screen par kuch nahi badla.
- **📥 Download queue (speed / ETA)**: live progress bar, speed, ETA, connections, retry count. Row par tap -> Pause / Resume / Cancel / Retry / Kholo / **File bhi delete**. Upar **⏯ Sab pause/resume** aur **🧹 Done saaf**. Notification me bhi total speed/ETA aur **Sab roko**.
- **Multi-connection**: ek file 1–8 hisson me (Range requests) parallel download hoti hai, phir jod kar save. Server Range support na de to apne aap 1 connection.
- **Batch queue**: **Naya URL** me kai links paste karo (har line me ek) -> **Sab queue me** ya sab ko ek saath **Schedule**. Ek saath kitni files chalein (1–4), baaki queue me wait karti hain.
- **Auto-retry**: net/server error par exponential backoff ke saath wahi se resume (max retry settings me). Internet ya Wi-Fi (agar "Sirf Wi-Fi") na ho to retry gine bina intezaar karta hai. App band ho jaye to Queue me **Resume**.
- **File type folders**: `Downloads/Lion/Videos | Audio | Images | Documents | Archives | APKs | Others/` (settings me on/off).
- **blob: / data: downloads**: jo sites JS se file banati hain (blob) unka download ab chalta hai; naam/extension blob type se banta hai. Blob link page me hi expire na hui ho tab chalta hai.
- **⚡ Download settings**: Fast engine on/off (off = purana Android DownloadManager), connections, parallel files, retry on/off + max retry, folder-by-type.
- Scheduled downloads bhi isi engine se chalte hain.

## 📜 History
- Floating window aur normal browser dono page visits save karte hain (max 300, SPA pages bhi).
- Normal browser me **Reload button ko dabaye rakho** -> History / Download queue / Download settings (screen par koi naya button nahi).
- ⚙ settings -> **📜 History**: search, tap = us window me kholo, long-press = ek entry hatao, **Sab saaf**, aur "History save karo" on/off.

## Text copy / paste
Floating window me text select karne par neeche Lion **Copy bar** aata hai:
**Copy** (clipboard me), **Select all**, **Paste** (input box me). System long-press menu bhi allowed hai.

## 📁 File Manager (v4.0, MyFiles app ab isi me)
- Start page par 4 button: 🌐 Normal Browser, 🪟 Floating Window, **📁 File Manager**, ✕ Floating band karo.
- File Manager = purani MyFiles app: 💽 AIC Disk, ☁️ HF Bucket, 📱 Phone Storage, copy/move (server par), video/music player, image viewer, Transfers. Code `files/` folder me hai (package `com.example.simplebrowser.files`).
- **Ek hi login**: File Manager aur Browser ka "☁ Server par bhejo" dono ek hi URL/username/password use karte hain (⚙ → Downloads → Server settings ya File Manager ka login, kahin bhi badlo, dono me badal jata hai).
- File Manager ka alag update checker nahi hai; Lion.apk ka hi auto-update chalta hai.
- Phone Storage ke liye pehli baar "All files access" permission do.
- File Manager ki screens safed background + kaale text me hain. Sirf video player aur image viewer dark rehte hain (media ke liye).
- Purani alag MyFiles app ab uninstall kar sakte ho.
- **Floating menu me 📁**: dot long-press -> pill me 📁 dabao -> File Manager khulta hai.
- **Upload par File Manager**: kisi bhi site (normal ya floating browser) par "file chuno" (input type=file) dabane par dialog aata hai: 📁 Lion File Manager (AIC / HF / Phone) ya 📱 system picker. File Manager me file par tap = chuni gayi; kai files wale upload me pehle lambi dabao, aur tap karke **✔ Bhejo**. Server ki file pehle phone ke cache me utarti hai (progress dikhta hai), phir site ko milti hai. Login na ho to seedha system picker.

## ⚡ Seedha server par download
- ⚙ → Downloads → Server settings me "⚡ Seedha server par download" on ho to link server ko jata hai; server (`/xfer/dl`, xfer.py) khud download karke chune hue folder me rakhta hai. Server se na ho to phone par purana tareeka.

## ⬇ Sab videos ek saath (listing / playlist page)
- Kisi page par 2+ video cards ho to neeche-right me **⬇ Sab** button aata hai (jab "Video par download icon" on ho).
- Dabane par page ke saare video links ki list (checkbox) khulti hai -> **☁ Server par bhejo** -> **quality / format chuno** (⭐ Best, 1080p, 720p… ya 🎵 sirf audio mp3). Quality ki list server se aati hai (chune hue pehle 3 videos ka union), jis video me wo quality na ho use sabse nazdeek wali milti hai. Har link server ke yt-dlp ko jata hai (cookie + referer ke saath) aur chune folder me save hota hai. Phone ka data/storage nahi lagta.
- Server me ek baar me 3 download chalte hain (env `XFER_DL_WORKERS`), queue 500 ki hai (`XFER_DL_QUEUE`): poora batch ek baar me server ko chala jaata hai, phir app band kar sakte ho. Queue bhar jaye (429) to purana tareeka: app 10-10 sec ruk ke bhejta rehta hai.
- Jobs server par file me save hoti hain (hf_tmp/state/xfer_jobs.json): restart ke baad download jobs dobara queue me aa jaati hain. `watch.sh` / `alert.sh` downloads chalte waqt container restart nahi karte (max 6 ghante wait).
- HLS (.m3u8) ke segments ek saath (6) khinche jaate hain (`XFER_HLS_PAR`, band karna ho `XFER_HLS_FAST=0`). Jo playlist sambhal na sake (live, fMP4, alag audio) wahan purana ffmpeg tareeka.
- Download se pehle dekhta hai ki wahi naam (extension ke bina) dest folder me AIC ya HF par pehle se hai to skip (`XFER_DUP_PRECHECK=0` se band).
- Disk kam hone par (`XFER_DISK_WARN_MB`, default 3072) Downloads screen par patti; batch khatam hone par Telegram message (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID).
- Jo video site se hata di gayi / private / country-lock hai, wo apne aap skip hoti hai aur agli video chalti rehti hai; aakhir me summary me "N site se hata di gayi (skip)" alag dikhta hai.
- Sirf server mode me chalta hai (⚙ -> Downloads -> Server settings me AIC/HF + login + folder). Page ko pehle neeche tak scroll karo taaki saare cards load ho jayein.
- Server side: `xfer.py` (naya, `_KEEP_JOBS=300`, yt-dlp naam/progress fix).

## 🔽 Floating window me dropdown (<select>)
- Overlay window me Android ka native dropdown popup nahi khulta. Ab floating browser me `<select>` par tap karte hi app apni list dikhata hai (title = field ka label, chuni hui value tick ke saath). Chunne par value set hoti hai aur site ko `input`/`change` event jata hai.
- Sirf normal `<select>` ke liye; custom (div wale) dropdown pehle se chalte hain. Multi-select aur iframe ke andar wale select abhi pehle jaise hain.

## 📥 Downloads (phone + server)
- Kahan: normal browser me reload button lamba dabao -> **📥 Downloads**; floating me ⚙ -> **📥 Downloads (phone + server)**.
- Ek screen me do hisse: **📱 Phone par** (speed/ETA/pause/resume pehle jaisa) aur **☁ Server par** (jo link server ko bheje: ⬇, ⬇ Sab, direct mode).
- Server rows ki state server se hi aati hai (har 3 sec): ⏳ queue me, ⬇ download ho raha (% aur size), ✅ **Server ne confirm kiya** (size + path jaise `aic:/video/x/naam.mp4`), 🚫 site se hata di gayi, ❌ fail (wajah), ❓ server ko yaad nahi (restart).
- Server row par tap: ✕ cancel, 🔍 **server par file check** (WebDAV se pooch ke size batata hai), 🔗 link copy, list se hatao. 🧹 "Done saaf" dono jagah ki khatam hui entries hata deta hai (files safe).
- Record phone me ~500 entries tak yaad rehta hai, to server restart ke baad bhi "ab tak ki downloads" dikhti hain.

## Pehli baar floating chalane par
- Notification permission (Android 13+) allow karo
- "Display over other apps" permission allow karo
- Notification me "Band karo" se floating band ho jata hai

## Default URL badalna
`app/src/main/java/com/example/simplebrowser/AppConfig.kt`
(ya floating window ke ⚙ settings se)

## Update bina uninstall ke (auto-update)
Build ke baad APK GitHub Release me `Lion.apk` naam se aati hai. App start par (aur ⚙ settings -> "Update check karo" se)
naya version dekhti hai; "Update karo" dabane par download karke install screen khulti hai, purani app ke upar update ho jati hai.
Ye isliye chalta hai kyunki har build ek hi permanent key (GitHub Secrets) se sign hoti hai aur versionCode har build par badhta hai.
Key (LION_SECRETS.txt / lion-release.jks) kabhi delete/badli mat karo, warna ek baar fir uninstall karna padega.

## Build (GitHub Actions)
Repo me `SimpleBrowser.zip` upload karo -> Actions me "Unzip and Build APK" chalegi -> repo ke **Releases** se `Lion.apk` download karo.
Signing ke liye repo Secrets me 4 cheezein chahiye: `LION_KEYSTORE_BASE64`, `LION_STORE_PASSWORD`, `LION_KEY_ALIAS`, `LION_KEY_PASSWORD`.
`.github/workflows/unzip.yml` ko haath se paste karna padta hai (GitHub me file kholo -> edit -> paste -> commit); zip ke andar wali workflow use nahi hoti.

## 📋 Paste se pehle compare (phone ↔ server)
- Phone / SD card se HF ya AIC me (ya ulta) paste dabate hi app pehle folder ke andar tak saari files compare karta hai (naya size vs wahan pehle se maujood size). Phir ek saath poochta hai: sab skip / same size skip + alag size replace / sab replace / dono rakho / ek-ek karke chuno (✔ = replace).
- Naye files (jo wahan nahi hain) seedha copy hote hain. Move me skip ki hui file source par rehti hai.
- Server ↔ server copy/move purane tareeke se (ek global choice).

## 🔌 SD card / USB pendrive (OTG): v4.1 (advance)
- **Sahi pehchaan**: USB ya SD ka faisla naam + OTG par lagi USB storage (UsbManager) se hota hai (pehle "pen" shabd se galat hota tha). Ek jaise naam wali do drive ho to UUID jud jata hai.
- **Live detect**: App chalte hi (process zinda) drive lagne par notification "💾/🔌 ... mil gayi: dabao aur kholo". Android 11+ par StorageVolumeCallback, purane par broadcast. Home / File Manager apne aap badalte hain. Bina eject nikalne par ⚠ notification.
- **Har halat dikhti hai**: Mounted ke alawa "check ho rahi", "khul nahi sakti (NTFS/RAW)", "mount nahi hai", "bina eject nikali gayi" bhi card me wajah ke saath. USB device lagi ho par Android ne volume na banaya ho to "USB device mili" card.
- **Info card**: filesystem (FAT32 / exFAT / NTFS), khaali / total, use% ki bar, read-only ya "likhna band (Android restriction)". Card lamba dabao: ℹ jaankari, ⏏ Safe eject, 🔄 dobara scan. File Manager ke ⋮ menu me bhi ℹ aur ⏏.
- **Safe eject**: Android app ko seedha unmount nahi karne deta. App check karti hai ki is drive par Lion ka koi transfer to nahi chal raha; phir Android Settings kholti hai jahan Eject dabate hain.
- **Safe copy**: shuru se pehle likhne ki permission aur jagah check; FAT32 par 4 GB se badi file pehle hi roki jati hai (saaf message); har file `.lionpart` naam se banti hai, fsync hota hai, size verify hota hai, tabhi asli naam me badalti hai. Drive beech me nikal jaye to poori job "Drive nikal gayi" bolkar ruk jati hai; purani adhoori `.lionpart` files agli baar apne aap saaf. Drive par File Manager khula ho aur drive nikle to wo screen band ho jati hai.
- Zaroori: Android 11+ par "All files access" ON ho.

## 📁 File Manager: search, favourites, live capacity (v4.2)
- **🔍 Search**: File Manager ke header me 🔍 (ya ⋮ → Search). Type karte hi usi folder ki list filter hoti hai. "Andar ke saare folders me bhi dhundo" tick karo to sab sub-folders me dhundta hai (results aate hi dikhte hain, har result ke neeche uska folder). Back / ✕ se band.
- **⭐ Favourites**: folder kholo aur header ka ☆ dabao. Kisi file / folder ko lamba dabao → ⋮ → "Favourite me jodo" (kai item ek saath bhi). Favourite ke naam ke aage ⭐ dikhta hai. ⋮ → "Favourites dekho" aur Home screen par "⭐ Favourites" section (tap = seedha wahin khulta hai; lamba dabao = hatao). AIC / HF / Phone / SD / USB sab par kaam karta hai.
- **Live capacity**: Home ke har card (AIC, HF, Phone, SD / USB) par "NN% khaali · X GB free" + use/total + rang wali patti, apne aap har ~3 sec me badalti hai. File Manager ke header me bhi. HF ke liye pehle server ka WebDAV quota dekhta hai; na mile to har 15 min me background me apne aap scan (Analyze dabana nahi padta). Home par neeche kheench kar refresh = sab abhi dobara (HF scan bhi).
- **Live list refresh**: folder ki list ab manual refresh ke bina badalti hai. Phone / SD par file aate-jaate turant (FileObserver + 3 sec check); AIC / HF par 6 / 10 sec me chupchap check, sirf badlav hone par list badalti hai (scroll nahi hilta).

## ⛅ HF2 Bucket (dusra HF account) — 2026-10-19
- Server par `/hf2/` (HF2_TOKEN + HF2_BUCKET). File Manager me teesra storage: **⛅ HF2 Bucket** (Home card, storage switcher, Analyze, Favourites, Paste/Copy/Move, player, image viewer — sab HF jaisa).
- HF ↔ HF2 copy/move server par hi hota hai (`/xfer`, store id `hf2`); alag account hone se file server ke temp se guzarti hai, isliye thoda slow.
- ⚙ Downloads → Server settings me ab "⛅ HF2 Bucket" bhi chun sakte ho (download seedha HF2 me).
- Login URL `/dav/`, `/hf/` ya `/hf2/` — kisi se bhi karo, teeno storage apne aap milte hain.
- HF2 card par abhi live capacity bar nahi hai (HF jaisa quota/scan sirf HF1 ke liye). Capacity dekhni ho to 🔍 Analyze → HF2.
- Is change ko Android Studio / Gradle me build karke dekhna baaki hai (ye zip bina compile ke banaya gaya).
