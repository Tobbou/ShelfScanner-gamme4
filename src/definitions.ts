export interface ShelfScannerPlugin {
  downloadModel(options?: { forceRedownload?: boolean }): Promise<{ success: boolean; modelPath: string }>;
  initializeEngine(options?: { backend?: string }): Promise<{ success: boolean; loadTimeMs: number; backend: string; gpuFallback?: boolean }>;
  startScanning(options?: { accumulationWindowMs?: number }): Promise<{ success: boolean; message: string }>;
  stopScanning(): Promise<{ success: boolean; totalProducts: number; products: any[] }>;
  getStatus(): Promise<{ modelDownloaded: boolean; modelLoaded: boolean; scanning: boolean; totalProductsFound: number }>;
  releaseEngine(): Promise<{ success: boolean }>;
  addListener(eventName: string, listener: (event: any) => void): Promise<{ remove: () => Promise<void> }>;
}
