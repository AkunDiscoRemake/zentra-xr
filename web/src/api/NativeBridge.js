/**
 * PINA VR - NativeBridge - Ponte com Android nativo
 * Recebe sensores de alta frequência (200Hz) do Kotlin via JavascriptInterface
 */

export class NativeBridge {
  constructor(sensorFusion) {
    this.sensorFusion = sensorFusion;
    this.isNative = false;
    this._setup();
  }

  _setup() {
    // Verifica se está rodando dentro do WebView Android com interface nativa
    if (window.PinaNative && window.PinaNative.getSensors) {
      this.isNative = true;
      console.log('[Pina Native] Bridge Android detectado - sensores nativos 200Hz');
      this._startNativeLoop();
    } else {
      // Cria stub para quando não está no Android, mas expõe função para Android injetar
      window.PinaNativeBridge = {
        onSensorData: (data) => {
          // data: { quat: [x,y,z,w], gyro: [x,y,z], accel: [x,y,z], mag: [x,y,z] }
          try {
            const parsed = typeof data === 'string' ? JSON.parse(data) : data;
            this.sensorFusion.onNativeSensors(parsed.quat, parsed.gyro, parsed.accel, parsed.mag);
          } catch(e) { console.warn('NativeBridge parse erro', e); }
        },
        onHandData: (data) => {
          // Futuro: hand tracking nativo via CameraX + MediaPipe Android
          console.log('[Pina Native] Hand data', data);
        }
      };

      // Escuta mensagens do Android via postMessage também
      window.addEventListener('message', (e) => {
        if (e.data && e.data.type === 'pina-sensors') {
          this.sensorFusion.onNativeSensors(e.data.quat, e.data.gyro, e.data.accel, e.data.mag);
        }
      });
    }
  }

  _startNativeLoop() {
    const loop = () => {
      try {
        const raw = window.PinaNative.getSensors(); // JSON string do Kotlin
        const data = JSON.parse(raw);
        this.sensorFusion.onNativeSensors(data.quat, data.gyro, data.accel, data.mag);
      } catch(e) {}
      requestAnimationFrame(loop);
    };
    loop();
  }

  // Envia comandos para o Android
  vibrate(pattern) {
    if (window.PinaNative && window.PinaNative.vibrate) {
      window.PinaNative.vibrate(JSON.stringify(pattern));
    } else if (navigator.vibrate) {
      navigator.vibrate(pattern);
    }
  }

  requestPermission(perm) {
    if (window.PinaNative && window.PinaNative.requestPermission) {
      window.PinaNative.requestPermission(perm);
    }
  }

  setMixedRealityMode(mode) {
    if (window.PinaNative && window.PinaNative.setMRMode) {
      window.PinaNative.setMRMode(mode);
    }
  }

  isNativeApp() { return this.isNative; }
}
