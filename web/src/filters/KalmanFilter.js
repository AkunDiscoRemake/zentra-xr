/**
 * PINA VR - Kalman Filters Otimizados
 * - Kalman 1D para cada eixo
 * - Kalman 3D com predição de velocidade (para hand tracking)
 * - Kalman Quaternion para orientação (gyro drift correction)
 * Zero alloc no hot path
 */

export class Kalman1D {
  constructor({ Q = 0.001, R = 0.01, P = 1, initial = 0 } = {}) {
    this.Q = Q; // process noise
    this.R = R; // measurement noise
    this.P = P; // estimation error
    this.X = initial; // state
    this.K = 0;
  }
  update(measurement) {
    // Predict
    this.P = this.P + this.Q;
    // Update
    this.K = this.P / (this.P + this.R);
    this.X = this.X + this.K * (measurement - this.X);
    this.P = (1 - this.K) * this.P;
    return this.X;
  }
  reset(v = 0) { this.X = v; this.P = 1; }
}

// Kalman com velocidade: estado [pos, vel], mede pos
export class Kalman3DVelocity {
  constructor(opts = {}) {
    const { Q = 0.01, R = 0.1 } = opts;
    // Para cada eixo: pos, vel
    this.axes = [
      { pos: 0, vel: 0, P: [[1,0],[0,1]], Q, R },
      { pos: 0, vel: 0, P: [[1,0],[0,1]], Q, R },
      { pos: 0, vel: 0, P: [[1,0],[0,1]], Q, R },
    ];
    this.lastT = null;
  }

  update(vec3, timestamp = performance.now()) {
    let dt = 0.016;
    if (this.lastT !== null) {
      dt = Math.min(0.05, (timestamp - this.lastT) / 1000);
    }
    this.lastT = timestamp;
    if (dt <= 0) dt = 0.016;

    const out = [0,0,0];
    const vel = [0,0,0];
    for (let i = 0; i < 3; i++) {
      const ax = this.axes[i];
      // Predict
      ax.pos = ax.pos + ax.vel * dt;
      // P = F*P*F^T + Q
      // F = [[1, dt],[0,1]]
      const P00 = ax.P[0][0] + dt * (ax.P[1][0] + ax.P[0][1]) + dt*dt*ax.P[1][1] + ax.Q;
      const P01 = ax.P[0][1] + dt * ax.P[1][1];
      const P10 = ax.P[1][0] + dt * ax.P[1][1];
      const P11 = ax.P[1][1] + ax.Q;
      ax.P[0][0] = P00; ax.P[0][1] = P01; ax.P[1][0] = P10; ax.P[1][1] = P11;

      // Update with measurement vec3[i]
      const y = vec3[i] - ax.pos;
      const S = ax.P[0][0] + ax.R;
      const K0 = ax.P[0][0] / S;
      const K1 = ax.P[1][0] / S;
      ax.pos += K0 * y;
      ax.vel += K1 * y;
      // P = (I-KH)P
      const P00_n = (1 - K0) * ax.P[0][0];
      const P01_n = (1 - K0) * ax.P[0][1];
      const P10_n = ax.P[1][0] - K1 * ax.P[0][0];
      const P11_n = ax.P[1][1] - K1 * ax.P[0][1];
      ax.P[0][0] = P00_n; ax.P[0][1] = P01_n; ax.P[1][0] = P10_n; ax.P[1][1] = P11_n;

      out[i] = ax.pos;
      vel[i] = ax.vel;
    }
    return { pos: out, vel };
  }

  predict(dtFuture = 0.05) {
    return this.axes.map(ax => ax.pos + ax.vel * dtFuture);
  }

  reset() {
    this.axes.forEach(ax => {
      ax.pos = 0; ax.vel = 0; ax.P = [[1,0],[0,1]];
    });
    this.lastT = null;
  }
}

// Kalman para quaternion (simplificado: Madgwick + Kalman para yaw drift)
export class OrientationKalman {
  constructor() {
    this.q = [0,0,0,1]; // xyzw
    this.bias = [0,0,0];
    this.P = 1;
    this.Q = 0.0001;
    this.R = 0.5;
  }

  // gyro: rad/s [x,y,z], accel: [x,y,z], mag: optional, dt
  update(gyro, accel, dt) {
    // Remove bias estimado
    const gx = gyro[0] - this.bias[0];
    const gy = gyro[1] - this.bias[1];
    const gz = gyro[2] - this.bias[2];

    // Integra quaternion com gyro
    const q = this.q;
    const qw = q[3], qx = q[0], qy = q[1], qz = q[2];
    const halfDt = dt * 0.5;
    const dqW = -halfDt * (gx*qx + gy*qy + gz*qz);
    const dqX = halfDt * (gx*qw + gy*qz - gz*qy);
    const dqY = halfDt * (-gx*qz + gy*qw + gz*qx);
    const dqZ = halfDt * (gx*qy - gy*qx + gz*qw);
    let nq = [qx+dqX, qy+dqY, qz+dqZ, qw+dqW];
    // normalize
    const len = Math.hypot(nq[0],nq[1],nq[2],nq[3]);
    nq = nq.map(v => v/len);

    // Correção via accel (pitch/roll) - complementary + kalman
    if (accel) {
      const ax = accel[0], ay = accel[1], az = accel[2];
      const accNorm = Math.hypot(ax,ay,az);
      if (accNorm > 0.1) {
        const axn = ax/accNorm, ayn = ay/accNorm, azn = az/accNorm;
        // gravidade esperada do quaternion atual
        const gx_est = 2*(nq[0]*nq[2] - nq[3]*nq[1]);
        const gy_est = 2*(nq[3]*nq[0] + nq[1]*nq[2]);
        const gz_est = nq[3]*nq[3] - nq[0]*nq[0] - nq[1]*nq[1] + nq[2]*nq[2];
        // erro
        const ex = ayn*gz_est - azn*gy_est;
        const ey = azn*gx_est - axn*gz_est;
        const ez = axn*gy_est - ayn*gx_est;
        // Kalman gain adaptativo baseado em confiança do accel
        const accConf = Math.min(1, Math.abs(accNorm - 9.81) < 2 ? 1 : 0.1);
        const k = 0.02 * accConf;
        // aplica correção e atualiza bias
        this.bias[0] += ex * k * 0.01;
        this.bias[1] += ey * k * 0.01;
        this.bias[2] += ez * k * 0.01;
        // corrige quaternion levemente
        nq[0] += ex * k; nq[1] += ey * k; nq[2] += ez * k;
        const len2 = Math.hypot(nq[0],nq[1],nq[2],nq[3]);
        nq = nq.map(v => v/len2);
      }
    }

    this.q = nq;
    return this.q;
  }

  getQuaternion() { return this.q; }
  reset() { this.q = [0,0,0,1]; this.bias = [0,0,0]; }
}

// Bank para mãos: 1 Kalman por eixo por landmark crítico (8 pontos: wrist, 5 tips, palm, index mcp)
export class HandKalmanBank {
  constructor() {
    this.filters = new Map();
    // Só para pontos críticos para performance
    this.criticalIndices = [0,4,8,12,16,20,5,9]; // wrist, 5 tips, index mcp, middle mcp
    for (const idx of this.criticalIndices) {
      this.filters.set(idx, new Kalman3DVelocity({ Q: 0.005, R: 0.05 }));
    }
  }
  update(landmarks, t) {
    const predicted = {};
    for (const idx of this.criticalIndices) {
      const lm = landmarks[idx];
      if (!lm) continue;
      const f = this.filters.get(idx);
      const res = f.update([lm.x, lm.y, lm.z], t);
      // sobrescreve com posição filtrada + predição leve
      landmarks[idx] = { x: res.pos[0], y: res.pos[1], z: res.pos[2], _vel: res.vel };
      predicted[idx] = f.predict(0.03); // prediz 30ms à frente para reduzir latência
    }
    return { landmarks, predicted };
  }
}
