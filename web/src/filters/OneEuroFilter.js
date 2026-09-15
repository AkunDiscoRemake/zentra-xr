/**
 * PINA VR - One Euro Filter
 * Otimizado para hand tracking 60fps + predição
 * Paper: Casiez et al. CHI 2012
 * Implementação de alta performance sem alocação no loop
 */

export class LowPassFilter {
  constructor(alpha = 0.5) {
    this.alpha = alpha;
    this.y = null;
    this.s = null;
  }
  setAlpha(alpha) { this.alpha = alpha; }
  filter(value, alpha = this.alpha) {
    if (this.y === null) {
      this.s = value;
      this.y = value;
      return value;
    }
    this.s = alpha * value + (1 - alpha) * this.s;
    return this.s;
  }
  lastValue() { return this.s; }
  reset() { this.y = null; this.s = null; }
}

export class OneEuroFilter {
  constructor(minCutoff = 1.2, beta = 0.007, dCutoff = 1.0) {
    this.minCutoff = minCutoff;
    this.beta = beta;
    this.dCutoff = dCutoff;
    this.xFilter = new LowPassFilter();
    this.dxFilter = new LowPassFilter();
    this.lastTime = null;
    this.freq = 60;
  }

  alpha(cutoff) {
    const te = 1.0 / this.freq;
    const tau = 1.0 / (2 * Math.PI * cutoff);
    return 1.0 / (1.0 + tau / te);
  }

  filter(value, timestamp = performance.now()) {
    if (this.lastTime === null) {
      this.lastTime = timestamp;
      this.freq = 60;
    } else {
      const dt = (timestamp - this.lastTime) / 1000;
      if (dt > 0) this.freq = 1.0 / dt;
      this.lastTime = timestamp;
    }

    const dvalue = this.xFilter.y !== null ? (value - this.xFilter.y) * this.freq : 0;
    const edvalue = this.dxFilter.filter(dvalue, this.alpha(this.dCutoff));
    const cutoff = this.minCutoff + this.beta * Math.abs(edvalue);
    const result = this.xFilter.filter(value, this.alpha(cutoff));
    this.xFilter.y = result;
    return result;
  }

  reset() {
    this.xFilter.reset();
    this.dxFilter.reset();
    this.lastTime = null;
  }
}

// Vetor 3D com 3 filtros OneEuro independentes, zero alloc após init
export class OneEuroFilterVec3 {
  constructor(minCutoff = 1.2, beta = 0.007, dCutoff = 1.0) {
    this.filters = [
      new OneEuroFilter(minCutoff, beta, dCutoff),
      new OneEuroFilter(minCutoff, beta, dCutoff),
      new OneEuroFilter(minCutoff, beta, dCutoff),
    ];
    this.out = [0,0,0];
  }
  filter(vec3, t) {
    this.out[0] = this.filters[0].filter(vec3[0], t);
    this.out[1] = this.filters[1].filter(vec3[1], t);
    this.out[2] = this.filters[2].filter(vec3[2], t);
    return this.out;
  }
  reset() { this.filters.forEach(f => f.reset()); }
}

// Para 21 landmarks da mão -> 21*3 = 63 filtros
export class HandOneEuroBank {
  constructor() {
    // Config otimizada para mãos: baixa latência, alto smoothness em repouso
    this.bank = Array.from({ length: 21 }, () => new OneEuroFilterVec3(1.5, 0.008, 1.2));
    // Filtros extras para palma (mais estável)
    this.palmFilter = new OneEuroFilterVec3(0.8, 0.002, 1.0);
    this.wristFilter = new OneEuroFilterVec3(0.7, 0.001, 0.9);
  }
  filterHand(landmarks, timestamp) {
    // landmarks: [{x,y,z}, 21]
    const filtered = new Array(21);
    for (let i = 0; i < 21; i++) {
      const lm = landmarks[i];
      if (i === 0) {
        const v = this.wristFilter.filter([lm.x, lm.y, lm.z], timestamp);
        filtered[i] = { x: v[0], y: v[1], z: v[2] };
      } else if (i <= 5) {
        const v = this.palmFilter.filter([lm.x, lm.y, lm.z], timestamp);
        filtered[i] = { x: v[0], y: v[1], z: v[2] };
      } else {
        const v = this.bank[i].filter([lm.x, lm.y, lm.z], timestamp);
        filtered[i] = { x: v[0], y: v[1], z: v[2] };
      }
    }
    return filtered;
  }
  reset() {
    this.bank.forEach(b => b.reset());
    this.palmFilter.reset();
    this.wristFilter.reset();
  }
}
