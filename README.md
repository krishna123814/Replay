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
