import { registerPlugin } from '@capacitor/core';

const ShelfScanner = registerPlugin('ShelfScanner', {
  web: () => import('./web').then(m => new m.ShelfScannerWeb()),
});

export { ShelfScanner };
