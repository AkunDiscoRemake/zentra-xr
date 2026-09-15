/**
 * PINA VR - XRManager - Cardboard Stereo Renderer
 * Renderização estéreo nativa para Cardboard, sem WebXR obrigatório (funciona em qualquer Android)
 * - Dois olhos com IPD
 * - Barrel distortion shader para lentes Cardboard
 * - Suporte a WebXR quando disponível
 */

import * as THREE from 'three';

export class XRManager {
  constructor(renderer, scene, camera) {
    this.renderer = renderer;
    this.scene = scene;
    this.camera = camera; // câmera mono central

    this.isCardboard = true;
    this.isWebXR = false;
    this.ipd = 0.064; // 64mm default
    this.fov = 80; // FOV por olho

    // Câmeras estéreo
    this.cameraL = new THREE.PerspectiveCamera(this.fov, 1, 0.1, 1000);
    this.cameraR = new THREE.PerspectiveCamera(this.fov, 1, 0.1, 1000);
    this.cameraL.layers.enable(1);
    this.cameraR.layers.enable(2);
    this.camera.layers.enable(1);
    this.camera.layers.enable(2);

    // Render targets para distorção
    this.renderTargetL = new THREE.WebGLRenderTarget(1024, 1024, { samples: 2 });
    this.renderTargetR = new THREE.WebGLRenderTarget(1024, 1024, { samples: 2 });

    // Quad final com distorção
    this._createDistortionQuad();

    this.sensorFusion = null;
    this.baseQuaternion = new THREE.Quaternion();
  }

  setSensorFusion(fusion) {
    this.sensorFusion = fusion;
  }

  _createDistortionQuad() {
    // Tela cheia com shader de distorção barrel para Cardboard v1/v2
    this.distortionScene = new THREE.Scene();
    this.distortionCamera = new THREE.OrthographicCamera(-1,1,1,-1,0,1);

    const geo = new THREE.PlaneGeometry(2,2);
    const mat = new THREE.ShaderMaterial({
      uniforms: {
        texL: { value: this.renderTargetL.texture },
        texR: { value: this.renderTargetR.texture },
        k1: { value: 0.2 }, // barrel distortion coef
        k2: { value: 0.05 },
        ipd: { value: this.ipd }
      },
      vertexShader: `varying vec2 vUv; void main(){ vUv=uv; gl_Position=vec4(position,1.0); }`,
      fragmentShader: `
        uniform sampler2D texL;
        uniform sampler2D texR;
        uniform float k1;
        uniform float k2;
        varying vec2 vUv;

        vec2 distort(vec2 uv) {
          vec2 centered = uv * 2.0 - 1.0;
          float r2 = dot(centered, centered);
          float distortion = 1.0 + k1 * r2 + k2 * r2 * r2;
          return centered * distortion * 0.5 + 0.5;
        }

        void main() {
          vec2 uv = vUv;
          // Divide tela ao meio: esquerda e direita
          if (uv.x < 0.5) {
            vec2 eyeUv = vec2(uv.x * 2.0, uv.y);
            eyeUv = distort(eyeUv);
            if (eyeUv.x < 0.0 || eyeUv.x > 1.0 || eyeUv.y < 0.0 || eyeUv.y > 1.0) {
              gl_FragColor = vec4(0.0,0.0,0.0,1.0);
            } else {
              gl_FragColor = texture2D(texL, eyeUv);
            }
          } else {
            vec2 eyeUv = vec2((uv.x - 0.5) * 2.0, uv.y);
            eyeUv = distort(eyeUv);
            if (eyeUv.x < 0.0 || eyeUv.x > 1.0 || eyeUv.y < 0.0 || eyeUv.y > 1.0) {
              gl_FragColor = vec4(0.0,0.0,0.0,1.0);
            } else {
              gl_FragColor = texture2D(texR, eyeUv);
            }
          }
        }
      `
    });
    this.distortionQuad = new THREE.Mesh(geo, mat);
    this.distortionScene.add(this.distortionQuad);
  }

  resize(width, height) {
    const eyeW = Math.floor(width/2);
    const eyeH = height;
    this.renderTargetL.setSize(eyeW, eyeH);
    this.renderTargetR.setSize(eyeW, eyeH);
    this.cameraL.aspect = eyeW / eyeH;
    this.cameraR.aspect = eyeW / eyeH;
    this.cameraL.updateProjectionMatrix();
    this.cameraR.updateProjectionMatrix();
    this.renderer.setSize(width, height);
  }

  updateFromSensors() {
    if (!this.sensorFusion) return;
    const q = this.sensorFusion.getQuaternion(); // [x,y,z,w]
    this.baseQuaternion.set(q[0], q[1], q[2], q[3]);
    this.camera.quaternion.copy(this.baseQuaternion);
    // Aplica offset IPD
    const halfIpd = this.ipd/2;
    this.cameraL.position.set(-halfIpd, 0, 0);
    this.cameraR.position.set(halfIpd, 0, 0);
    this.cameraL.quaternion.copy(this.baseQuaternion);
    this.cameraR.quaternion.copy(this.baseQuaternion);
    // Em VR, posição da cabeça é 0, mas rotação vem do sensor
    this.cameraL.position.applyQuaternion(this.baseQuaternion);
    this.cameraR.position.applyQuaternion(this.baseQuaternion);
    // Mantém posição central
    this.cameraL.position.add(this.camera.position);
    this.cameraR.position.add(this.camera.position);
  }

  render() {
    this.updateFromSensors();

    if (this.isCardboard) {
      // Render estéreo manual para Cardboard
      const width = this.renderer.domElement.width || window.innerWidth;
      const height = this.renderer.domElement.height || window.innerHeight;

      // Olho esquerdo
      this.renderer.setRenderTarget(this.renderTargetL);
      this.renderer.setScissorTest(false);
      this.renderer.render(this.scene, this.cameraL);

      // Olho direito
      this.renderer.setRenderTarget(this.renderTargetR);
      this.renderer.render(this.scene, this.cameraR);

      // Final com distorção
      this.renderer.setRenderTarget(null);
      this.renderer.render(this.distortionScene, this.distortionCamera);
    } else {
      // Mono fallback (sem Cardboard, debug)
      this.renderer.setRenderTarget(null);
      this.renderer.render(this.scene, this.camera);
    }
  }

  setIPD(ipd) {
    this.ipd = ipd;
    if (this.distortionQuad) this.distortionQuad.material.uniforms.ipd.value = ipd;
  }

  // Tenta WebXR se disponível (para headsets que suportam)
  async tryWebXR() {
    if (!navigator.xr) return false;
    try {
      const supported = await navigator.xr.isSessionSupported('immersive-vr');
      if (supported) {
        this.isWebXR = true;
        console.log('[Pina XR] WebXR disponível, mas mantendo Cardboard como primary');
        // Não força WebXR, mantém Cardboard como default pedido
        return true;
      }
    } catch(e) {}
    return false;
  }

  // Para dev API: obter matrizes de projeção
  getEyeMatrices() {
    return {
      left: { position: this.cameraL.position.clone(), quaternion: this.cameraL.quaternion.clone(), projection: this.cameraL.projectionMatrix.clone() },
      right: { position: this.cameraR.position.clone(), quaternion: this.cameraR.quaternion.clone(), projection: this.cameraR.projectionMatrix.clone() }
    };
  }
}
