# Lion (Android)

Ek app, do mode:
1. **Normal Browser** – full-screen WebView browser (URL bar, back/forward/reload, search), status bar hidden (immersive), file upload.
2. **Floating Window** – kisi bhi app ke upar tairta golden 🦁 dot + browser window.

## Floating dot + pill menu
- Startup par sirf chhota golden dot dikhta hai (default 16dp, settings me 10–28dp).
- Dot tap -> browser window minimize/open (toggle). Dot long-press (dabaye rakho) -> pill menu: ◀ back, ⟳ refresh, ⛶ maximize, 🪟 window show/hide, ⚙ settings, ✕ close. Dot ko drag karke move/❌ zone me band.
  4 sec koi action na ho to pill apne aap band.
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
- blob:/data: wale downloads abhi support nahi.

## Text copy / paste
Floating window me text select karne par neeche Lion **Copy bar** aata hai:
**Copy** (clipboard me), **Select all**, **Paste** (input box me). System long-press menu bhi allowed hai.

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
