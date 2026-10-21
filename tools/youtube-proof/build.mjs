import { build } from 'esbuild';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root = dirname(fileURLToPath(import.meta.url));
await build({
    entryPoints: [resolve(root, 'proof.js')],
    outfile: resolve(root, '../../android/app/src/main/assets/youtube-proof/proof.js'),
    bundle: true, format: 'iife', target: 'chrome80', legalComments: 'inline', minify: true
});
