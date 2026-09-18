# BlackBrowser

Minimal Android WebView browser with a pure-black UI (address bar, back/forward/reload, progress bar).

- Single `MainActivity.kt`, no Compose — plain View + `android.webkit.WebView`
- Theme forces pure black background/status bar/nav bar
- CI builds a debug APK on every push to `main` (`.github/workflows/build-apk.yml`)
