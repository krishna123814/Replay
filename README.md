# Lion (Android)

Ek app, do mode:
1. **Normal Browser** – full-screen WebView browser (URL bar, back/forward/reload, search), status bar hidden (immersive), file upload.
2. **Floating Window** – kisi bhi app ke upar tairta golden 🦁 dot + browser window.

## Floating dot + pill menu
- Startup par sirf chhota golden dot dikhta hai (default 16dp, settings me 10–28dp).
- Dot tap -> chhota pill menu: ◀ back, ⟳ refresh, ⛶ maximize, 🪟 window show/hide, ⚙ settings, ✕ close.
  4 sec koi action na ho to pill apne aap band.
- Dot ko kahin bhi drag karo (position yaad rehti hai). Drag karte waqt neeche ❌ zone me chhodo to band.
- Browser window: upar ke patle handle se drag, ◢ corner se resize, ⛶ se maximize (status bar tak poori screen).
- ⚙ settings: website URL, icon size, dot opacity, dot size.
- File upload (input type=file), JS prompt, fullscreen video chalte hain.
- Login/external links system browser me khulte hain.

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

## Build (GitHub Actions)
Repo me `SimpleBrowser.zip` upload karo -> Actions me "Unzip and Build APK" chalegi ->
Artifacts se `SimpleBrowser-debug-apk` download karo.
