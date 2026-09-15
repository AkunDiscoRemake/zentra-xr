/**
 * PINA VR - Dev API para Android
 * window.PinaVR - API completa para criar apps espaciais
 */

export class PinaAPI {
  constructor({ sensorFusion, handTracking, depthTouch, spatialUI, spatialBrowser, xrManager, mr, pinaSaber }) {
    this.sensorFusion = sensorFusion;
    this.handTracking = handTracking;
    this.depthTouch = depthTouch;
    this.spatialUI = spatialUI;
    this.browser = spatialBrowser;
    this.xr = xrManager;
    this.mr = mr;
    this.game = pinaSaber;

    this.version = '1.0.0-pina';
    this.isPina = true;

    this._expose();
  }

  _expose() {
    const api = {
      version: this.version,
      // Sensores
      sensors: {
        onOrientation: (cb) => this.sensorFusion.onOrientation(cb),
        getQuaternion: () => this.sensorFusion.getQuaternion(),
        getEuler: () => this.sensorFusion.getEuler(),
        reset: () => this.sensorFusion.reset(),
        mode: () => this.sensorFusion.mode
      },
      // Hand tracking
      hands: {
        onHands: (cb) => this.handTracking.onHands(cb),
        getHands: () => this.handTracking.getHands(),
        isActive: () => this.handTracking.isActive
      },
      // Touch 6DOF
      touch: {
        registerObject: (mesh, cbs) => this.depthTouch.registerObject(mesh, cbs),
        unregisterObject: (mesh) => this.depthTouch.unregisterObject(mesh),
        on: (ev, cb) => this.depthTouch.on(ev, cb),
        threshold: (v) => { if (v!==undefined) this.depthTouch.touchThreshold = v; return this.depthTouch.touchThreshold; }
      },
      // UI 3D
      ui: {
        createButton: (opts) => this.spatialUI.createButton(opts),
        createPanel: (opts) => this.spatialUI.createPanel(opts),
        createSlider: (opts) => this.spatialUI.createSlider(opts),
        createCurvedScreen: (opts) => this.spatialUI.createCurvedScreen(opts),
        clear: () => this.spatialUI.clear()
      },
      // Browser espacial
      browser: {
        createWindow: (opts) => this.browser.createWindow(opts),
        open: (url, title) => this.browser.open(url, title),
        close: (id) => this.browser.close(id),
        getWindows: () => this.browser.getWindows(),
        openYouTube: () => this.browser.openYouTube(),
        openDiscord: () => this.browser.openDiscord(),
        shortcuts: this.browser.shortcuts
      },
      // XR
      xr: {
        setIPD: (ipd) => this.xr.setIPD(ipd),
        getEyeMatrices: () => this.xr.getEyeMatrices(),
        resize: (w,h) => this.xr.resize(w,h)
      },
      // Mixed Reality
      mr: {
        setMode: (mode) => this.mr.setMode(mode),
        getMode: () => this.mr.mode,
        isActive: () => this.mr.isActive
      },
      // Jogos
      games: {
        pinaSaber: {
          start: () => this.game.start(),
          stop: () => this.game.stop(),
          getScore: () => this.game.getScore(),
          isPlaying: () => this.game.isPlaying
        }
      },
      // Sistema
      system: {
        vibrate: (pattern) => { if (navigator.vibrate) navigator.vibrate(pattern); },
        isAndroid: () => /Android/.test(navigator.userAgent),
        isCardboard: () => this.xr.isCardboard,
        requestFullscreen: async () => {
          try { await document.documentElement.requestFullscreen(); } catch(e){}
        }
      },
      // Eventos globais
      on: (event, cb) => {
        if (event === 'hands') return this.handTracking.onHands(cb);
        if (event === 'orientation') return this.sensorFusion.onOrientation(cb);
        if (event === 'touch') return this.depthTouch.on('click', cb);
      },
      // Para Android Native Bridge
      _native: {
        onSensors: (quat, gyro, accel, mag) => this.sensorFusion.onNativeSensors(quat, gyro, accel, mag)
      }
    };

    window.PinaVR = api;
    window.ZentraXR = api; // compatibilidade com nome antigo
    window.Pina = api;

    console.log('%c PINA VR OS %c Dev API pronta - window.PinaVR ', 'background:#00ff88;color:#000;padding:4px 8px;border-radius:4px 0 0 4px;font-weight:bold', 'background:#1a1a2e;color:#00ff88;padding:4px 8px;border-radius:0 4px 4px 0;');
    console.log('PinaVR:', api);
  }
}
