import { registerPlugin } from '@capacitor/core';

import type { ShelfScannerPlugin } from './definitions';

const ShelfScanner = registerPlugin<ShelfScannerPlugin>('ShelfScanner', {
  web: () => import('./web').then(m => new m.ShelfScannerWeb()),
});

export * from './definitions';
export { ShelfScanner };
