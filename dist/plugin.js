var capacitorShelfScanner = (function (exports, core) {
    'use strict';

    const ShelfScanner = core.registerPlugin('ShelfScanner', {
        web: () => Promise.resolve().then(function () { return web; }).then(m => new m.ShelfScannerWeb()),
    });

    class ShelfScannerWeb extends core.WebPlugin {
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

    var web = /*#__PURE__*/Object.freeze({
        __proto__: null,
        ShelfScannerWeb: ShelfScannerWeb
    });

    exports.ShelfScanner = ShelfScanner;

    Object.defineProperty(exports, '__esModule', { value: true });

    return exports;

})({}, capacitorExports);
