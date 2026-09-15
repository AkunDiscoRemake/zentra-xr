/**
 * PINA VR - Passthrough Mixed Reality
 * Modo default = Mixed Reality
 * Usa câmera traseira como background, mapeada em esfera invertida com shader de profundidade
 */

import * as THREE from 'three';

export class PassthroughMR {
  constructor(scene, renderer) {
    this.scene = scene;
    this.renderer = renderer;
    this.mode = 'mixed'; // 'mixed' | 'vr' | 'passthrough-only'
    this.video = null;
    this.texture = null;
    this.mesh = null;
    this.isActive = false;
    this.depthMap = null;
  }

  async start() {
    try {
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          facingMode: { ideal: 'environment' },
          width: { ideal: 1920 },
          height: { ideal: 1080 },
          frameRate: { ideal: 60 }
        },
        audio: false
      });

      this.video = document.createElement('video');
      this.video.srcObject = stream;
      this.video.autoplay = true;
      this.video.playsInline = true;
      this.video.muted = true;
      await this.video.play();

      this.texture = new THREE.VideoTexture(this.video);
      this.texture.colorSpace = THREE.SRGBColorSpace;
      this.texture.minFilter = THREE.LinearFilter;
      this.texture.magFilter = THREE.LinearFilter;

      this._createMRMesh();
      this.isActive = true;
      console.log('[Pina MR] Passthrough iniciado - MIXED REALITY DEFAULT');
      return true;
    } catch (e) {
      console.warn('[Pina MR] Câmera falhou, fallback para VR:', e);
      this.mode = 'vr';
      this._createVRSkybox();
      return false;
    }
  }

  _createMRMesh() {
    // Esfera invertida gigante para passthrough com correção de distorção
    const geo = new THREE.SphereGeometry(50, 64, 64);
    // Inverte normais
    geo.scale(-1,1,1);

    const mat = new THREE.ShaderMaterial({
      uniforms: {
        map: { value: this.texture },
        time: { value: 0 },
        mode: { value: 0 }, // 0=mixed, 1=vr
        opacity: { value: 1.0 }
      },
      vertexShader: `
        varying vec2 vUv;
        varying vec3 vWorldPos;
        void main() {
          vUv = uv;
          vWorldPos = (modelMatrix * vec4(position, 1.0)).xyz;
          gl_Position = projectionMatrix * modelViewMatrix * vec4(position, 1.0);
        }
      `,
      fragmentShader: `
        uniform sampler2D map;
        uniform float time;
        uniform float mode;
        uniform float opacity;
        varying vec2 vUv;
        varying vec3 vWorldPos;
        
        void main() {
          // Correção de UV para câmera: espelha e ajusta FOV
          vec2 uv = vUv;
          uv.x = 1.0 - uv.x; // mirror
          
          // Barrel distortion invertida para Cardboard
          vec2 centered = uv * 2.0 - 1.0;
          float r2 = dot(centered, centered);
          float distortion = 1.0 + 0.1 * r2 + 0.05 * r2 * r2;
          centered *= distortion;
          uv = centered * 0.5 + 0.5;
          
          vec4 tex = texture2D(map, uv);
          
          if (mode < 0.5) {
            // MIXED REALITY: passthrough + leve escurecimento nas bordas para UI
            float vignette = 1.0 - length(centered) * 0.3;
            vignette = clamp(vignette, 0.6, 1.0);
            // Depth cue: escurece um pouco o fundo para objetos 3D saltarem
            tex.rgb *= vignette * 0.85;
            // Adiciona grid sutil de MR
            float grid = step(0.99, sin(vWorldPos.x * 2.0)) + step(0.99, sin(vWorldPos.z * 2.0));
            tex.rgb += grid * 0.02;
            gl_FragColor = vec4(tex.rgb, opacity);
          } else {
            // VR MODE: skybox escuro
            vec3 vrColor = vec3(0.02, 0.02, 0.04);
            // Nebula sutil
            float neb = sin(vWorldPos.x*0.1 + time*0.05) * sin(vWorldPos.y*0.1) * 0.05;
            vrColor += neb;
            gl_FragColor = vec4(vrColor, 1.0);
          }
        }
      `,
      side: THREE.BackSide,
      depthWrite: false,
      depthTest: false
    });

    this.mesh = new THREE.Mesh(geo, mat);
    this.mesh.renderOrder = -1000;
    this.mesh.frustumCulled = false;
    this.scene.add(this.mesh);

    // Cria também plano de chão para MR (estimativa)
    const floorGeo = new THREE.PlaneGeometry(100,100);
    const floorMat = new THREE.MeshBasicMaterial({ 
      color: 0x111111, 
      transparent: true, 
      opacity: 0.15,
      visible: false // só em debug
    });
    this.floor = new THREE.Mesh(floorGeo, floorMat);
    this.floor.rotation.x = -Math.PI/2;
    this.floor.position.y = -1.6;
    this.scene.add(this.floor);
  }

  _createVRSkybox() {
    const geo = new THREE.SphereGeometry(50, 32, 32);
    geo.scale(-1,1,1);
    const mat = new THREE.ShaderMaterial({
      uniforms: { time: { value: 0 } },
      vertexShader: `varying vec3 vPos; void main(){ vPos=position; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }`,
      fragmentShader: `
        varying vec3 vPos;
        uniform float time;
        void main(){
          vec3 dir = normalize(vPos);
          float t = dir.y * 0.5 + 0.5;
          vec3 top = vec3(0.05,0.02,0.15);
          vec3 bottom = vec3(0.01,0.01,0.02);
          vec3 col = mix(bottom, top, t);
          // estrelas
          float stars = step(0.998, sin(dir.x*200.0)*sin(dir.y*200.0)*sin(dir.z*200.0));
          col += stars * 0.8;
          gl_FragColor = vec4(col,1.0);
        }
      `,
      side: THREE.BackSide,
      depthWrite: false
    });
    this.mesh = new THREE.Mesh(geo, mat);
    this.scene.add(this.mesh);
  }

  setMode(mode) {
    this.mode = mode; // 'mixed' | 'vr'
    if (this.mesh && this.mesh.material.uniforms && this.mesh.material.uniforms.mode) {
      this.mesh.material.uniforms.mode.value = mode === 'vr' ? 1.0 : 0.0;
    }
    console.log(`[Pina MR] Modo: ${mode.toUpperCase()}`);
  }

  update(time) {
    if (this.mesh && this.mesh.material.uniforms) {
      if (this.mesh.material.uniforms.time) this.mesh.material.uniforms.time.value = time;
    }
  }

  stop() {
    if (this.video && this.video.srcObject) {
      this.video.srcObject.getTracks().forEach(t => t.stop());
    }
    this.isActive = false;
  }
}
