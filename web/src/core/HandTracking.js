/**
 * PINA VR - Hand Tracking com MediaPipe + One Euro + Kalman
 * - MediaPipe Tasks Vision (WASM) - roda local, sem servidor
 * - OneEuroFilter por landmark (21 pontos)
 * - Kalman 3D para predição
 * - 6DOF: posição + orientação da mão
 * - Depth estimation via tamanho da mão e z do MediaPipe
 */

import { HandOneEuroBank } from '../filters/OneEuroFilter.js';
import { HandKalmanBank } from '../filters/KalmanFilter.js';

export class HandTracking {
  constructor() {
    this.isActive = false;
    this.video = null;
    this.detector = null;
    this.oneEuro = new HandOneEuroBank();
    this.kalman = new HandKalmanBank();
    this.hands = []; // [{ keypoints, handedness, worldLandmarks, etc }]
    this.listeners = [];
    this.lastTime = 0;
    this.frontCameraStream = null;

    // Config depth 6DOF
    this.focalLength = 800; // estimado, calibrado por tamanho médio da mão (0.2m)
    this.avgHandSize = 0.2; // 20cm largura média

    // Canvas para debug
    this.canvas = null;
    this.ctx = null;
  }

  async start() {
    try {
      // Câmera frontal para hand tracking
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: 'user',
          width: { ideal: 640 },
          height: { ideal: 480 },
          frameRate: { ideal: 30 }
        },
        audio: false
      });
      this.frontCameraStream = stream;

      this.video = document.createElement('video');
      this.video.srcObject = stream;
      this.video.autoplay = true;
      this.video.playsInline = true;
      this.video.muted = true;
      await this.video.play();

      // Tenta carregar MediaPipe Tasks Vision
      await this._loadMediaPipe();

      this.isActive = true;
      this._loop();
      console.log('[Pina Hands] Hand Tracking iniciado - MediaPipe + OneEuro + Kalman');
      return true;
    } catch (e) {
      console.warn('[Pina Hands] Falha câmera frontal ou MediaPipe, usando fallback mock para testes:', e);
      // Fallback: modo simulação para desktop/teste sem câmera
      this.isActive = true;
      this._loopMock();
      return false;
    }
  }

  async _loadMediaPipe() {
    // Usa CDN para MediaPipe Tasks Vision
    // @ts-ignore
    const vision = await import('https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs');
    const { FilesetResolver, HandLandmarker } = vision;

    const fileset = await FilesetResolver.forVisionTasks(
      'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm'
    );

    this.detector = await HandLandmarker.createFromOptions(fileset, {
      baseOptions: {
        modelAssetPath: 'https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task',
        delegate: 'GPU'
      },
      numHands: 2,
      runningMode: 'VIDEO',
      minHandDetectionConfidence: 0.5,
      minHandPresenceConfidence: 0.5,
      minTrackingConfidence: 0.5
    });

    console.log('[Pina Hands] MediaPipe HandLandmarker carregado');
  }

  _loop() {
    const step = async () => {
      if (!this.isActive) return;
      const now = performance.now();
      if (now - this.lastTime < 33) { // ~30fps
        requestAnimationFrame(step);
        return;
      }
      this.lastTime = now;

      try {
        if (this.detector && this.video && this.video.readyState >= 2) {
          const result = this.detector.detectForVideo(this.video, now);
          if (result && result.landmarks && result.landmarks.length > 0) {
            this._processDetections(result, now);
          } else {
            this.hands = [];
          }
        }
      } catch (e) {
        console.warn('[Pina Hands] detect erro', e);
      }

      this._notify();
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }

  _loopMock() {
    // Mock para teste sem câmera: mãos seguindo mouse/touch simulado
    let t = 0;
    const step = () => {
      if (!this.isActive) return;
      t += 0.016;
      // Simula mão direita aberta no centro
      const mockHand = {
        handedness: 'Right',
        keypoints: this._generateMockHand(Math.sin(t)*0.2, Math.cos(t*0.5)*0.1, 0.5 + Math.sin(t*0.7)*0.1),
        worldLandmarks: [],
        depth: 0.5,
        pinch: Math.sin(t*2) > 0.5,
        pinchStrength: (Math.sin(t*2)+1)/2
      };
      this.hands = [mockHand];
      this._notify();
      requestAnimationFrame(step);
    };
    step();
  }

  _generateMockHand(x,y,z) {
    // 21 pontos mock
    const pts = [];
    for (let i=0;i<21;i++) {
      pts.push({
        x: 0.5 + x + (i%3)*0.02,
        y: 0.5 + y + Math.floor(i/3)*0.02,
        z: z + i*0.001,
        visibility: 1
      });
    }
    return pts;
  }

  _processDetections(result, timestamp) {
    const hands = [];
    for (let h=0; h<result.landmarks.length; h++) {
      const landmarks = result.landmarks[h]; // 21 pontos {x,y,z}
      const handedness = result.handednesses[h]?.[0]?.categoryName || 'Unknown';
      const worldLandmarks = result.worldLandmarks?.[h] || landmarks;

      // Aplica One Euro
      const filtered = this.oneEuro.filterHand(landmarks, timestamp);
      // Aplica Kalman nos pontos críticos
      const kalmanRes = this.kalman.update(filtered, timestamp);
      const finalLandmarks = kalmanRes.landmarks;

      // Calcula 6DOF: posição, orientação, profundidade
      const dof6 = this._compute6DOF(finalLandmarks, worldLandmarks, handedness);

      // Detecta gestos: pinch, grab, etc
      const gesture = this._detectGesture(finalLandmarks);

      hands.push({
        index: h,
        handedness,
        keypoints: finalLandmarks,
        worldLandmarks,
        ...dof6,
        ...gesture,
        predicted: kalmanRes.predicted
      });
    }
    this.hands = hands;
  }

  _compute6DOF(landmarks, worldLandmarks, handedness) {
    // Posição da palma (média entre wrist e MCPs)
    const wrist = landmarks[0];
    const indexMCP = landmarks[5];
    const pinkyMCP = landmarks[17];
    const middleMCP = landmarks[9];

    const palmX = (wrist.x + indexMCP.x + pinkyMCP.x + middleMCP.x)/4;
    const palmY = (wrist.y + indexMCP.y + pinkyMCP.y + middleMCP.y)/4;
    const palmZ = (wrist.z + indexMCP.z + pinkyMCP.z + middleMCP.z)/4;

    // Converte de espaço normalizado da imagem para espaço 3D VR
    // x: -1 a 1 (esquerda-direita), y: -1 a 1 (cima-baixo), z: profundidade 0-1m
    const x3d = (palmX - 0.5) * 2 * 1.5; // escala para alcance de braço
    const y3d = (0.5 - palmY) * 2 * 1.0 + 1.2; // altura média
    const zDepth = this._estimateDepth(landmarks); // metros

    const z3d = -zDepth; // frente é -Z em Three.js

    // Orientação: vetor da palma
    const palmDir = {
      x: indexMCP.x - pinkyMCP.x,
      y: indexMCP.y - pinkyMCP.y,
      z: indexMCP.z - pinkyMCP.z
    };
    const forward = {
      x: middleMCP.x - wrist.x,
      y: middleMCP.y - wrist.y,
      z: middleMCP.z - wrist.z
    };

    // Quaternion aproximado da mão (simplificado)
    // Calcula rotação a partir de vetores
    const yaw = Math.atan2(forward.x, -forward.z);
    const pitch = Math.atan2(forward.y, Math.hypot(forward.x, forward.z));
    const roll = Math.atan2(palmDir.y, palmDir.x);

    return {
      position: [x3d, y3d, z3d],
      rotation: { yaw, pitch, roll },
      depth: zDepth,
      palm: { x: palmX, y: palmY, z: palmZ },
      confidence: 1.0
    };
  }

  _estimateDepth(landmarks) {
    // Estima profundidade via tamanho da mão na imagem
    // Largura entre index MCP e pinky MCP em pixels normalizados
    const indexMCP = landmarks[5];
    const pinkyMCP = landmarks[17];
    const dist = Math.hypot(indexMCP.x - pinkyMCP.x, indexMCP.y - pinkyMCP.y);
    if (dist < 0.001) return 0.5;
    // depth = (realSize * focal) / imageSize
    const depth = (this.avgHandSize * this.focalLength * 0.001) / dist;
    // Clamp 0.1m a 1.5m
    return Math.min(1.5, Math.max(0.1, depth));
  }

  _detectGesture(landmarks) {
    // Pinch: distância thumb tip (4) e index tip (8)
    const thumbTip = landmarks[4];
    const indexTip = landmarks[8];
    const pinchDist = Math.hypot(thumbTip.x-indexTip.x, thumbTip.y-indexTip.y, (thumbTip.z-indexTip.z)*0.5);
    const pinch = pinchDist < 0.05;
    const pinchStrength = Math.max(0, 1 - pinchDist/0.1);

    // Grab: todos os dedos fechados
    const tips = [8,12,16,20].map(i=>landmarks[i]);
    const mcp = [5,9,13,17].map(i=>landmarks[i]);
    let closed = 0;
    for (let i=0;i<4;i++) {
      const d = Math.hypot(tips[i].x - mcp[i].x, tips[i].y - mcp[i].y);
      if (d < 0.08) closed++;
    }
    const grab = closed >= 3;
    const grabStrength = closed/4;

    // Point: só index estendido
    const middleClosed = Math.hypot(landmarks[12].x - landmarks[9].x, landmarks[12].y - landmarks[9].y) < 0.08;
    const point = !middleClosed ? false : (Math.hypot(indexTip.x - landmarks[5].x, indexTip.y - landmarks[5].y) > 0.15);

    // Palm open
    const palmOpen = closed === 0;

    return { pinch, pinchStrength, grab, grabStrength, point, palmOpen, pinchDist };
  }

  onHands(cb) { this.listeners.push(cb); return () => { this.listeners = this.listeners.filter(f=>f!==cb); }; }

  _notify() {
    for (const cb of this.listeners) cb(this.hands);
  }

  getHands() { return this.hands; }

  stop() {
    this.isActive = false;
    if (this.frontCameraStream) {
      this.frontCameraStream.getTracks().forEach(t=>t.stop());
    }
  }
}
