/**
 * PINA VR - Main - Bootstrap do OS
 * Mixed Reality como default, tudo 3D spatial
 */

import * as THREE from 'three';
import { CSS3DRenderer } from 'three/addons/renderers/CSS3DRenderer.js';

import { SensorFusion } from './core/SensorFusion.js';
import { XRManager } from './core/XRManager.js';
import { PassthroughMR } from './core/PassthroughMR.js';
import { HandTracking } from './core/HandTracking.js';
import { DepthTouch } from './core/DepthTouch.js';
import { SpatialUI } from './core/SpatialUI.js';
import { SpatialBrowser } from './browser/SpatialBrowser.js';
import { PinaSaber } from './games/PinaSaber.js';
import { PinaAPI } from './api/PinaAPI.js';
import { NativeBridge } from './api/NativeBridge.js';

class PinaVROS {
  constructor() {
    this.scene = new THREE.Scene();
    this.cssScene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(75, window.innerWidth/window.innerHeight, 0.1, 1000);
    this.camera.position.set(0, 1.6, 0);

    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false });
    this.renderer.setSize(window.innerWidth, window.innerHeight);
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.xr.enabled = false; // Cardboard manual, não WebXR obrigatório
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    document.getElementById('app').appendChild(this.renderer.domElement);

    this.cssRenderer = new CSS3DRenderer();
    this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
    this.cssRenderer.domElement.style.position = 'absolute';
    this.cssRenderer.domElement.style.top = '0';
    this.cssRenderer.domElement.style.pointerEvents = 'none'; // deixa raycast 3D controlar
    document.getElementById('app').appendChild(this.cssRenderer.domElement);

    // Core
    this.sensorFusion = new SensorFusion();
    this.xrManager = new XRManager(this.renderer, this.scene, this.camera);
    this.xrManager.setSensorFusion(this.sensorFusion);
    this.passthrough = new PassthroughMR(this.scene, this.renderer);
    this.handTracking = new HandTracking();
    this.depthTouch = new DepthTouch(this.scene, this.handTracking);
    this.spatialUI = new SpatialUI(this.scene, this.depthTouch);
    this.spatialBrowser = new SpatialBrowser(this.scene, this.cssScene, this.depthTouch, this.spatialUI);
    this.pinaSaber = new PinaSaber(this.scene, this.handTracking, this.spatialUI, this.depthTouch);
    this.nativeBridge = new NativeBridge(this.sensorFusion);

    // API
    this.api = new PinaAPI({
      sensorFusion: this.sensorFusion,
      handTracking: this.handTracking,
      depthTouch: this.depthTouch,
      spatialUI: this.spatialUI,
      spatialBrowser: this.spatialBrowser,
      xrManager: this.xrManager,
      mr: this.passthrough,
      pinaSaber: this.pinaSaber
    });

    this.clock = new THREE.Clock();
    this.isStarted = false;

    this._setupLights();
    this._setupUI();
    this._bindEvents();
  }

  _setupLights() {
    const ambient = new THREE.AmbientLight(0x404040, 1.5);
    this.scene.add(ambient);
    const dir = new THREE.DirectionalLight(0xffffff, 1);
    dir.position.set(2,5,2);
    this.scene.add(dir);
    const neon = new THREE.PointLight(0x00ff88, 2, 10);
    neon.position.set(0,2,-2);
    this.scene.add(neon);
  }

  _setupUI() {
    // Tela de boot / calibração - 100% 3D
    this.bootPanel = this.spatialUI.createPanel({ position: [0,1.6,-1.2], size: [1.4,0.9,0.02], title: 'PINA VR OS // BOOT' });

    this.spatialUI.createButton({
      label: '▶ INICIAR PINA VR',
      position: [0,1.6,-1.15],
      size: [0.8,0.15,0.03],
      color: 0x00ff88,
      onClick: () => this.start()
    });

    this.spatialUI.createButton({
      label: 'MR MODE (DEFAULT)',
      position: [-0.35,1.4,-1.15],
      size: [0.5,0.1,0.02],
      color: 0x1a1a2e,
      onClick: () => this.passthrough.setMode('mixed')
    });

    this.spatialUI.createButton({
      label: 'VR MODE',
      position: [0.35,1.4,-1.15],
      size: [0.5,0.1,0.02],
      color: 0x1a1a2e,
      onClick: () => this.passthrough.setMode('vr')
    });

    this.spatialUI.createButton({
      label: '🎮 PINA SABER',
      position: [0,1.2,-1.15],
      size: [0.6,0.12,0.02],
      color: 0xff0066,
      onClick: () => { this.pinaSaber.start(); }
    });

    // Info de sensores
    const infoCanvas = document.createElement('canvas');
    infoCanvas.width = 1024; infoCanvas.height = 256;
    this.infoCtx = infoCanvas.getContext('2d');
    const infoTex = new THREE.CanvasTexture(infoCanvas);
    const infoGeo = new THREE.PlaneGeometry(1.2,0.3);
    const infoMat = new THREE.MeshBasicMaterial({ map: infoTex, transparent: true });
    this.infoPanel = new THREE.Mesh(infoGeo, infoMat);
    this.infoPanel.position.set(0,0.8,-1.2);
    this.scene.add(this.infoPanel);
    this.infoTex = infoTex;
    this.infoCanvas = infoCanvas;
  }

  _bindEvents() {
    window.addEventListener('resize', () => {
      this.camera.aspect = window.innerWidth/window.innerHeight;
      this.camera.updateProjectionMatrix();
      this.xrManager.resize(window.innerWidth, window.innerHeight);
      this.cssRenderer.setSize(window.innerWidth, window.innerHeight);
    });

    // Click para iniciar sensores (requer gesto do usuário no Android)
    document.addEventListener('click', () => {
      if (!this.isStarted) {
        // Não inicia automaticamente, espera botão 3D
      }
    }, { once: true });
  }

  async start() {
    if (this.isStarted) return;
    this.isStarted = true;

    document.getElementById('loading').style.display = 'none';

    // Inicia tudo em paralelo
    await Promise.all([
      this.sensorFusion.start(),
      this.passthrough.start(),
      this.handTracking.start()
    ]);

    // Remove boot panel após iniciar e mostra dock
    setTimeout(() => {
      this.spatialUI.clear();
      this._createMainDock();
      // Abre YouTube e Discord como exemplo de multitask
      this.spatialBrowser.openYouTube();
      setTimeout(()=> this.spatialBrowser.openDiscord(), 800);
    }, 1000);

    console.log('%c PINA VR OS INICIADO ', 'background:#00ff88;color:#000;font-size:20px;padding:8px;border-radius:8px;');
    console.log('Mixed Reality default, Cardboard stereo, Hand tracking com OneEuro+Kalman');

    this._loop();
  }

  _createMainDock() {
    const dockY = 0.2;
    const dockZ = -1.2;

    this.spatialUI.createButton({
      label: 'MR',
      position: [-0.6, dockY, dockZ],
      size: [0.18,0.18,0.02],
      color: 0x00ff88,
      onClick: () => this.passthrough.setMode('mixed')
    });

    this.spatialUI.createButton({
      label: 'VR',
      position: [-0.35, dockY, dockZ],
      size: [0.18,0.18,0.02],
      color: 0x333333,
      onClick: () => this.passthrough.setMode('vr')
    });

    this.spatialUI.createButton({
      label: 'YT',
      position: [-0.1, dockY, dockZ],
      size: [0.18,0.18,0.02],
      color: 0xff0000,
      onClick: () => this.spatialBrowser.openYouTube()
    });

    this.spatialUI.createButton({
      label: 'DC',
      position: [0.15, dockY, dockZ],
      size: [0.18,0.18,0.02],
      color: 0x5865f2,
      onClick: () => this.spatialBrowser.openDiscord()
    });

    this.spatialUI.createButton({
      label: '🎮',
      position: [0.4, dockY, dockZ],
      size: [0.18,0.18,0.02],
      color: 0xff0066,
      onClick: () => this.pinaSaber.isPlaying ? this.pinaSaber.stop() : this.pinaSaber.start()
    });

    this.spatialUI.createButton({
      label: '⌂',
      position: [0.65, dockY, dockZ],
      size: [0.18,0.18,0.02],
      color: 0x1a1a2e,
      onClick: () => { this.spatialBrowser._rearrange(); }
    });

    // Slider IPD
    this.spatialUI.createSlider({
      position: [0, dockY-0.25, dockZ],
      min: 0.05,
      max: 0.08,
      value: 0.064,
      onChange: (v) => this.xrManager.setIPD(v)
    });
  }

  _loop() {
    const animate = () => {
      const dt = this.clock.getDelta();
      const time = this.clock.getElapsedTime();

      this.passthrough.update(time);
      this.depthTouch.update();
      this.spatialUI.update(time);
      this.pinaSaber.update(dt, time);

      // Atualiza info de sensores
      if (this.isStarted && this.infoCtx) {
        const q = this.sensorFusion.getQuaternion();
        const e = this.sensorFusion.getEuler();
        const hands = this.handTracking.getHands();
        this.infoCtx.clearRect(0,0,1024,256);
        this.infoCtx.fillStyle = 'rgba(0,0,0,0.6)';
        this.infoCtx.fillRect(0,0,1024,256);
        this.infoCtx.fillStyle = '#00ff88';
        this.infoCtx.font = '20px monospace';
        this.infoCtx.fillText(`PINA VR // MR:${this.passthrough.mode.toUpperCase()} | SENSORS:${this.sensorFusion.mode} | FPS:${Math.round(1/dt)}`, 20, 30);
        this.infoCtx.fillStyle = '#ffffff';
        this.infoCtx.font = '16px monospace';
        this.infoCtx.fillText(`GYRO: [${this.sensorFusion.gyro.map(v=>v.toFixed(2)).join(', ')}] | ACCEL: [${this.sensorFusion.accel.map(v=>v.toFixed(2)).join(', ')}]`, 20, 60);
        this.infoCtx.fillText(`QUAT: [${q.map(v=>v.toFixed(3)).join(', ')}] | EULER: ${e.x.toFixed(2)},${e.y.toFixed(2)},${e.z.toFixed(2)}`, 20, 85);
        this.infoCtx.fillText(`HANDS: ${hands.length} | ${hands.map(h=>`${h.handedness} depth:${h.depth?.toFixed(2)}m pinch:${h.pinch?'YES':''}`).join(' | ')}`, 20, 110);
        this.infoCtx.fillText(`TOUCH: threshold ${this.depthTouch.touchThreshold}m | BROWSER: ${this.spatialBrowser.windows.length} janelas | SABER: ${this.pinaSaber.isPlaying?'PLAYING':''} score ${this.pinaSaber.score}`, 20, 135);
        this.infoCtx.fillStyle = '#00ff88';
        this.infoCtx.fillText(`> Use mãos na frente da câmera frontal para touch 6DOF direto. Pinch para clicar.`, 20, 170);
        this.infoTex.needsUpdate = true;
      }

      // Render
      this.xrManager.render();
      this.cssRenderer.render(this.cssScene, this.camera);

      requestAnimationFrame(animate);
    };
    animate();
  }
}

// Boot
window.addEventListener('DOMContentLoaded', () => {
  window.pinaOS = new PinaVROS();
});
