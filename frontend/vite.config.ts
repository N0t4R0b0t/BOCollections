import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

// Vite's dev static-file server has no mime mapping for .apk, so it serves the download link
// with an empty Content-Type — Chrome then falls back to sniffing the file's bytes, and since
// an APK is a ZIP archive under the hood, it gets saved with a .zip-looking type/name instead
// of the `download="bocollections-debug.apk"` filename actually asked for. Force the right
// type before Vite's static middleware runs, and reject any later attempt to overwrite it.
function apkContentType(): Plugin {
  return {
    name: 'apk-content-type',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url?.split('?')[0].endsWith('.apk')) {
          const setHeader = res.setHeader.bind(res);
          res.setHeader = ((name: string, value: unknown) =>
            name.toLowerCase() === 'content-type'
              ? setHeader('Content-Type', 'application/vnd.android.package-archive')
              : setHeader(name, value as never)) as typeof res.setHeader;
        }
        next();
      });
    },
  };
}

export default defineConfig({
  plugins: [apkContentType(), react()],
  server: {
    host: true,
    // Coder's port-forwarding proxies through a generated subdomain, which Vite's
    // Host-header check would otherwise reject as an unrecognized host.
    allowedHosts: true,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    sourcemap: false,
    minify: 'terser',
  },
})
