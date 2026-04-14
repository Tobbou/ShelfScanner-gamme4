import { WebPlugin } from '@capacitor/core';
import type { ShelfScannerPlugin } from './definitions';

export class ShelfScannerWeb extends WebPlugin implements ShelfScannerPlugin {
  async downloadModel(): Promise<any> {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async initializeEngine(): Promise<any> {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async startScanning(): Promise<any> {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async stopScanning(): Promise<any> {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async getStatus(): Promise<any> {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
  async releaseEngine(): Promise<any> {
    throw this.unavailable('ShelfScanner is only available on Android.');
  }
}
