# Simple Browser (Android)

Ek app, do mode:
1. **Normal Browser** – full-screen WebView browser (URL bar, back/forward/reload, search).
2. **Floating Window** – kisi bhi app ke upar tairti browser window:
   - ✥ se drag, ◢ corner se resize, ⛶ maximize, — minimize (bubble)
   - bubble ko neeche ❌ zone me kheench kar band karo
   - ⚙ settings: URL, menu left/right, icon size, bubble opacity, menu doori
   - file upload (input type=file), JS prompt, fullscreen video
   - login/external links system browser me khulte hain

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
