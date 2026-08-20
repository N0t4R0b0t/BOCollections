import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'dev.rsalvador.bocollections',
  appName: 'BOCollections',
  webDir: 'dist',
  // Dev-mode live reload: the WebView loads straight from the existing Vite dev server (through
  // the same nginx/Coder forwarding chain already set up for browser testing) instead of the
  // bundled dist/ assets, so ongoing JS/React changes keep hot-reloading with no APK rebuild.
  // Switch this back to bundled assets (remove `server`) before any real release build.
  server: {
    url: 'https://boc-dev.nj-server2.local',
    cleartext: false,
  },
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

export default config;
