// Copies the opencv.js WASM build bundled inside node_modules/jscanify into public/vendor/ so
// PhotoCropModal can lazy-load it same-origin via a plain <script> tag instead of Vite trying to
// parse/bundle a ~9MB file into the app's main chunk. Regenerated on every `npm install` — see
// frontend/public/vendor/ in .gitignore.
import { copyFileSync, existsSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(dirname(fileURLToPath(import.meta.url)));
const src = join(root, 'node_modules/jscanify/src/opencv.js');
const destDir = join(root, 'public/vendor');
const dest = join(destDir, 'opencv.js');

if (!existsSync(src)) {
  console.warn('[copy-opencv] node_modules/jscanify/src/opencv.js not found, skipping');
  process.exit(0);
}

mkdirSync(destDir, { recursive: true });
copyFileSync(src, dest);
console.log('[copy-opencv] copied opencv.js to public/vendor/');
