import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'dev.rsalvador.bocollections',
  appName: 'BOCollections',
  webDir: 'dist',
  plugins: {
    CapacitorHttp: {
      // Its native HTTP bridge doesn't always parse JSON responses the way axios expects
      // (confirmed on a real device — login worked, then the very next call crashed with
      // "e.map is not a function" because the response body came back as a raw string instead
      // of a parsed array). We don't need its CORS-bypass benefit — the backend already has this
      // origin in app.cors.allowed-origins — so just use the WebView's normal fetch/XHR instead.
      enabled: false,
    },
  },
};

// Dev-mode live reload: set CAPACITOR_DEV_SERVER_URL before `npx cap sync android` (or opening
// Android Studio) to point the WebView straight at a running Vite dev server instead of the
// bundled dist/ assets, so ongoing JS/React changes keep hot-reloading with no APK rebuild.
// Deliberately not hardcoded here (it used to be, pointed at a personal dev domain) — leave it
// unset for any real build. Without it the app serves the bundled dist/ and asks the user to
// Connect to a server at runtime instead of being baked in at build time (see ConnectServerPage).
if (process.env.CAPACITOR_DEV_SERVER_URL) {
  config.server = { url: process.env.CAPACITOR_DEV_SERVER_URL, cleartext: false };
}

export default config;
