import { WebPlugin } from '@capacitor/core';

export class ShelfScannerWeb extends WebPlugin {
  async downloadModel() {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async initializeEngine() {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async startScanning() {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async stopScanning() {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async getStatus() {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async releaseEngine() {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
}
