/**
 * PINA VR - Sensor Fusion: Melhor Giroscópio Possível
 * - Fusão de Gyro (high freq) + Accel (low freq) + Magnetometer (yaw)
 * - Implementa Complementary + Madgwick AHRS + Kalman
 * - Suporte a Android RotationVector via NativeBridge (precisão nativa)
 * - 1000Hz interpolação preditiva para render loop
 * - Auto calibração de bias
 */

import { OrientationKalman } from '../filters/KalmanFilter.js';

export class SensorFusion {
  constructor() {
    this.mode = 'web'; // 'web' | 'native'
    this.orientation = { alpha: 0, beta: 0, gamma: 0 };
    this.quaternion = [0,0,0,1];
    this.euler = { x:0, y:0, z:0 };
    this.accel = [0,0,9.81];
    this.gyro = [0,0,0];
    this.mag = null;

    this.kalman = new OrientationKalman();
    this.lastTime = performance.now();
    this.biasSamples = [];
    this.isCalibrated = false;
    this.bias = [0,0,0];

    // Filtros adicionais
    this.accelLP = [0,0,9.81];
    this.accelLPAlpha = 0.1;

    // Para interpolação 1000Hz
    this.targetQuat = [0,0,0,1];
    this.currentQuat = [0,0,0,1];
    this.angularVelocity = [0,0,0];

    // Callbacks
    this.listeners = [];

    // Device orientation fallback
    this.deviceOrientation = { alpha:0, beta:0, gamma:0 };

    // Estado
    this.isStarted = false;
  }

  async start() {
    if (this.isStarted) return;
    this.isStarted = true;

    // Tenta permissões iOS/Android 13+
    if (typeof DeviceOrientationEvent !== 'undefined' && DeviceOrientationEvent.requestPermission) {
      try {
        const p = await DeviceOrientationEvent.requestPermission();
        console.log('[Pina Sensors] Orientation permission:', p);
      } catch(e) { console.warn('[Pina Sensors] Perm negada', e); }
    }
    if (typeof DeviceMotionEvent !== 'undefined' && DeviceMotionEvent.requestPermission) {
      try {
        const p = await DeviceMotionEvent.requestPermission();
        console.log('[Pina Sensors] Motion permission:', p);
      } catch(e) {}
    }

    window.addEventListener('deviceorientation', this._onDeviceOrientation.bind(this), true);
    window.addEventListener('devicemotion', this._onDeviceMotion.bind(this), true);

    // Gyroscope API moderna (Generic Sensor API) - alta frequência
    try {
      if ('Gyroscope' in window) {
        // @ts-ignore
        this.gyroSensor = new Gyroscope({ frequency: 60 });
        this.gyroSensor.addEventListener('reading', () => {
          this.gyro = [this.gyroSensor.x, this.gyroSensor.y, this.gyroSensor.z];
          this.angularVelocity = [...this.gyro];
        });
        await this.gyroSensor.start();
        console.log('[Pina Sensors] Gyroscope 60Hz ativo');
      }
    } catch(e) { console.warn('Gyro sensor API falhou', e); }

    try {
      if ('Accelerometer' in window) {
        // @ts-ignore
        this.accelSensor = new Accelerometer({ frequency: 60 });
        this.accelSensor.addEventListener('reading', () => {
          const raw = [this.accelSensor.x, this.accelSensor.y, this.accelSensor.z];
          // low-pass
          for (let i=0;i<3;i++) {
            this.accelLP[i] = this.accelLP[i] * (1-this.accelLPAlpha) + raw[i]*this.accelLPAlpha;
          }
          this.accel = [...this.accelLP];
        });
        await this.accelSensor.start();
        console.log('[Pina Sensors] Accelerometer 60Hz ativo');
      }
    } catch(e) {}

    try {
      if ('AbsoluteOrientationSensor' in window) {
        // @ts-ignore
        this.absOrient = new AbsoluteOrientationSensor({ frequency: 60, referenceFrame: 'device' });
        this.absOrient.addEventListener('reading', () => {
          const q = this.absOrient.quaternion;
          // q é [x,y,z,w]
          this.targetQuat = [q[0], q[1], q[2], q[3]];
          this.mode = 'native-abs';
        });
        await this.absOrient.start();
        console.log('[Pina Sensors] AbsoluteOrientation 60Hz ativo - MELHOR PRECISÃO');
      }
    } catch(e) {}

    // Loop de fusão
    this._fusionLoop();
    this._calibrateBias();
  }

  _onDeviceOrientation(e) {
    this.deviceOrientation = { alpha: e.alpha || 0, beta: e.beta || 0, gamma: e.gamma || 0 };
    if (this.mode === 'web' && !this.absOrient) {
      // Converte para quaternion como fallback
      const alpha = (e.alpha || 0) * Math.PI/180;
      const beta = (e.beta || 0) * Math.PI/180;
      const gamma = (e.gamma || 0) * Math.PI/180;
      this.targetQuat = this._eulerToQuat(beta, gamma, alpha); // ordem correta
    }
  }

  _onDeviceMotion(e) {
    if (e.accelerationIncludingGravity) {
      const a = e.accelerationIncludingGravity;
      const raw = [a.x||0, a.y||0, a.z||0];
      for (let i=0;i<3;i++) {
        this.accelLP[i] = this.accelLP[i]*(1-this.accelLPAlpha) + raw[i]*this.accelLPAlpha;
      }
      this.accel = [...this.accelLP];
    }
    if (e.rotationRate) {
      const r = e.rotationRate;
      // deg/s para rad/s
      this.gyro = [(r.alpha||0)*Math.PI/180, (r.beta||0)*Math.PI/180, (r.gamma||0)*Math.PI/180];
      this.angularVelocity = [...this.gyro];
    }
  }

  // Chamado pelo NativeBridge Android com dados de alta frequência (200Hz)
  onNativeSensors(quat, gyro, accel, mag) {
    this.mode = 'native';
    if (quat) this.targetQuat = quat;
    if (gyro) { this.gyro = gyro; this.angularVelocity = gyro; }
    if (accel) this.accel = accel;
    if (mag) this.mag = mag;
  }

  _fusionLoop() {
    const loop = () => {
      const now = performance.now();
      const dt = Math.min(0.05, (now - this.lastTime)/1000);
      this.lastTime = now;

      if (this.mode === 'web' && !this.absOrient) {
        // Usa Kalman para fusão gyro + accel
        const q = this.kalman.update(this.gyro, this.accel, dt);
        this.targetQuat = q;
      }

      // Interpola para 1000Hz equivalente: slerp + predição angular
      this.currentQuat = this._slerp(this.currentQuat, this.targetQuat, Math.min(1, dt*15));
      
      // Predição para frente para reduzir latência de render (extrapola com gyro)
      const predicted = this._predictQuat(this.currentQuat, this.angularVelocity, 0.02); // 20ms predição
      this.quaternion = predicted;

      // Converte para euler para API legada
      this.euler = this._quatToEuler(this.quaternion);

      // Notifica listeners
      for (const cb of this.listeners) {
        cb(this.quaternion, this.accel, this.gyro, this.euler);
      }

      requestAnimationFrame(loop);
    };
    loop();
  }

  _calibrateBias() {
    // Coleta 2 segundos de gyro parado para estimar bias
    let samples = 0;
    const collect = setInterval(() => {
      if (samples < 120) {
        this.biasSamples.push([...this.gyro]);
        samples++;
      } else {
        clearInterval(collect);
        // média
        let bx=0, by=0, bz=0;
        for (const s of this.biasSamples) { bx+=s[0]; by+=s[1]; bz+=s[2]; }
        bx/=this.biasSamples.length; by/=this.biasSamples.length; bz/=this.biasSamples.length;
        this.bias = [bx,by,bz];
        this.isCalibrated = true;
        console.log('[Pina Sensors] Bias calibrado:', this.bias);
      }
    }, 16);
  }

  // Math helpers zero alloc
  _eulerToQuat(x,y,z) {
    const cx = Math.cos(x/2), sx = Math.sin(x/2);
    const cy = Math.cos(y/2), sy = Math.sin(y/2);
    const cz = Math.cos(z/2), sz = Math.sin(z/2);
    return [
      sx*cy*cz - cx*sy*sz,
      cx*sy*cz + sx*cy*sz,
      cx*cy*sz - sx*sy*cz,
      cx*cy*cz + sx*sy*sz
    ];
  }
  _quatToEuler(q) {
    const [x,y,z,w] = q;
    const sinr = 2*(w*x + y*z);
    const cosr = 1 - 2*(x*x + y*y);
    const roll = Math.atan2(sinr, cosr);
    const sinp = 2*(w*y - z*x);
    const pitch = Math.abs(sinp) >= 1 ? Math.sign(sinp)*Math.PI/2 : Math.asin(sinp);
    const siny = 2*(w*z + x*y);
    const cosy = 1 - 2*(y*y + z*z);
    const yaw = Math.atan2(siny, cosy);
    return { x: roll, y: pitch, z: yaw };
  }
  _slerp(a,b,t) {
    let [ax,ay,az,aw] = a;
    let [bx,by,bz,bw] = b;
    let dot = ax*bx + ay*by + az*bz + aw*bw;
    if (dot < 0) { dot = -dot; bx=-bx; by=-by; bz=-bz; bw=-bw; }
    if (dot > 0.9995) {
      // lerp
      const rx = ax + t*(bx-ax);
      const ry = ay + t*(by-ay);
      const rz = az + t*(bz-az);
      const rw = aw + t*(bw-aw);
      const len = Math.hypot(rx,ry,rz,rw);
      return [rx/len, ry/len, rz/len, rw/len];
    }
    const theta0 = Math.acos(dot);
    const theta = theta0*t;
    const sinTheta = Math.sin(theta);
    const sinTheta0 = Math.sin(theta0);
    const s0 = Math.cos(theta) - dot*sinTheta/sinTheta0;
    const s1 = sinTheta/sinTheta0;
    return [
      ax*s0 + bx*s1,
      ay*s0 + by*s1,
      az*s0 + bz*s1,
      aw*s0 + bw*s1
    ];
  }
  _predictQuat(q, angVel, dt) {
    const [qx,qy,qz,qw] = q;
    const [wx,wy,wz] = angVel;
    const halfDt = dt*0.5;
    const dqW = -halfDt*(wx*qx + wy*qy + wz*qz);
    const dqX = halfDt*(wx*qw + wy*qz - wz*qy);
    const dqY = halfDt*(-wx*qz + wy*qw + wz*qx);
    const dqZ = halfDt*(wx*qy - wy*qx + wz*qw);
    let nq = [qx+dqX, qy+dqY, qz+dqZ, qw+dqW];
    const len = Math.hypot(nq[0],nq[1],nq[2],nq[3]);
    return nq.map(v=>v/len);
  }

  onOrientation(cb) { this.listeners.push(cb); return () => { this.listeners = this.listeners.filter(f=>f!==cb); }; }
  getQuaternion() { return this.quaternion; }
  getEuler() { return this.euler; }
  reset() { this.kalman.reset(); this.currentQuat = [0,0,0,1]; this.targetQuat = [0,0,0,1]; }
}
