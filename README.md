# Simple Browser (Android)

Ek chhota WebView browser: URL bar, back/forward/reload, progress bar,
search (Google) aur http/https dono links.

## APK kaise banaye (bina Android Studio ke)
1. GitHub par naya repo banao aur is poore folder ki files upload karo (`.github` folder ke saath).
2. Repo ke **Actions** tab par jao. Build apne aap chalegi (ya "Build APK" > "Run workflow").
3. Build poori hone par us run ke neeche **Artifacts** se `SimpleBrowser-debug-apk` download karo.
4. Zip khol kar `app-debug.apk` phone me install karo (Settings me "Install unknown apps" allow karna padega).

## Home page badalna
`app/src/main/java/com/example/simplebrowser/MainActivity.kt` me `HOME_URL` badal do.

## Android Studio se banana ho to
Folder open karo, Gradle sync hone do, phir Build > Build APK(s).
