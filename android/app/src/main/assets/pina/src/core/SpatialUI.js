/**
 * PINA VR - Spatial UI - 100% 3D, zero 2D
 * Todos os elementos são meshes 3D no espaço
 * Botões, painéis, sliders, tudo com raycast de mão
 */

import * as THREE from 'three';

export class SpatialUI {
  constructor(scene, depthTouch) {
    this.scene = scene;
    this.depthTouch = depthTouch;
    this.elements = [];
    this.group = new THREE.Group();
    this.scene.add(this.group);
  }

  createButton({ label = 'BTN', position = [0,1.5,-1.5], size = [0.4,0.12,0.02], color = 0x1a1a2e, onTouch = null, onClick = null } = {}) {
    const geo = new THREE.BoxGeometry(...size);
    // Rounded box via shader
    const mat = new THREE.ShaderMaterial({
      uniforms: {
        color: { value: new THREE.Color(color) },
        hover: { value: 0.0 },
        active: { value: 0.0 },
        time: { value: 0 }
      },
      vertexShader: `
        varying vec3 vPos;
        void main() { vPos=position; gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0); }
      `,
      fragmentShader: `
        uniform vec3 color;
        uniform float hover;
        uniform float active;
        uniform float time;
        varying vec3 vPos;
        void main() {
          // SDF rounded box
          vec3 c = color;
          c += hover * 0.3;
          c += active * 0.5;
          // Glow animado
          float glow = sin(time*3.0 + vPos.x*5.0) * 0.05 * hover;
          c += glow;
          // Borda
          float border = step(0.48, abs(vPos.x/0.2)) + step(0.48, abs(vPos.y/0.06));
          if (border > 0.5) c += 0.2;
          gl_FragColor = vec4(c, 1.0);
        }
      `
    });

    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(...position);
    mesh.userData.label = label;
    mesh.userData.isButton = true;

    // Texto 3D via canvas texture (mas renderizado em mesh 3D, não DOM)
    const canvas = document.createElement('canvas');
    canvas.width = 512; canvas.height = 128;
    const ctx = canvas.getContext('2d');
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 48px monospace';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(label, 256, 64);
    const tex = new THREE.CanvasTexture(canvas);
    const textGeo = new THREE.PlaneGeometry(size[0]*0.9, size[1]*0.8);
    const textMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true });
    const textMesh = new THREE.Mesh(textGeo, textMat);
    textMesh.position.z = size[2]/2 + 0.001;
    mesh.add(textMesh);

    // Interação via DepthTouch
    this.depthTouch.registerObject(mesh, {
      onHover: () => { mat.uniforms.hover.value = 1.0; },
      onHoverEnd: () => { mat.uniforms.hover.value = 0.0; },
      onTouchStart: () => { mat.uniforms.active.value = 1.0; if (onTouch) onTouch(); },
      onTouchEnd: () => { mat.uniforms.active.value = 0.0; },
      onClick: () => { 
        // Click anim
        mat.uniforms.active.value = 1.0;
        setTimeout(()=> mat.uniforms.active.value = 0.0, 200);
        if (onClick) onClick();
        if (onTouch) onTouch();
      }
    });

    this.group.add(mesh);
    this.elements.push(mesh);

    // Update loop para time
    const update = (t) => { mat.uniforms.time.value = t; };
    mesh.userData.update = update;

    return mesh;
  }

  createPanel({ position = [0,1.5,-2], size = [1.2,0.8,0.02], color = 0x0f0f1e, title = '' } = {}) {
    const geo = new THREE.BoxGeometry(...size);
    const mat = new THREE.MeshStandardMaterial({ 
      color, 
      metalness: 0.3, 
      roughness: 0.4,
      transparent: true,
      opacity: 0.9
    });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(...position);
    mesh.userData.isPanel = true;

    if (title) {
      const canvas = document.createElement('canvas');
      canvas.width = 1024; canvas.height = 128;
      const ctx = canvas.getContext('2d');
      ctx.fillStyle = '#00ff88';
      ctx.font = 'bold 56px monospace';
      ctx.fillText(title, 20, 80);
      const tex = new THREE.CanvasTexture(canvas);
      const titleGeo = new THREE.PlaneGeometry(size[0]*0.95, 0.15);
      const titleMat = new THREE.MeshBasicMaterial({ map: tex, transparent: true });
      const titleMesh = new THREE.Mesh(titleGeo, titleMat);
      titleMesh.position.set(0, size[1]/2 - 0.1, size[2]/2+0.001);
      mesh.add(titleMesh);
    }

    this.group.add(mesh);
    this.elements.push(mesh);
    return mesh;
  }

  createSlider({ position, min=0, max=1, value=0.5, onChange } = {}) {
    const trackGeo = new THREE.BoxGeometry(0.5, 0.02, 0.01);
    const trackMat = new THREE.MeshBasicMaterial({ color: 0x333333 });
    const track = new THREE.Mesh(trackGeo, trackMat);
    track.position.set(...position);

    const knobGeo = new THREE.SphereGeometry(0.03, 16,16);
    const knobMat = new THREE.MeshBasicMaterial({ color: 0x00ff88 });
    const knob = new THREE.Mesh(knobGeo, knobMat);
    const range = 0.5 - 0.06;
    knob.position.x = (value - 0.5)*range*2;
    track.add(knob);

    this.depthTouch.registerObject(knob, {
      onTouchMove: (data) => {
        // Move knob baseado em posição da mão
        // Simplificado: usa X da mão relativo ao track
        const handX = data.fingerPos.x - track.getWorldPosition(new THREE.Vector3()).x;
        const clamped = Math.max(-range, Math.min(range, handX));
        knob.position.x = clamped;
        const newVal = (clamped/range +1)/2 * (max-min) + min;
        if (onChange) onChange(newVal);
      }
    });

    this.group.add(track);
    return track;
  }

  createCurvedScreen({ position, width=1.6, height=0.9, radius=2, content = null } = {}) {
    // Tela curva cilíndrica para browser
    const segments = 32;
    const geo = new THREE.CylinderGeometry(radius, radius, height, segments, 1, true, -width/radius/2, width/radius);
    const mat = new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide });
    if (content && content.texture) mat.map = content.texture;
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(...position);
    // Olha para origem
    mesh.lookAt(0, position[1], 0);
    mesh.rotation.y += Math.PI;

    this.group.add(mesh);
    return mesh;
  }

  update(time) {
    this.elements.forEach(el => {
      if (el.userData.update) el.userData.update(time*0.001);
    });
  }

  clear() {
    this.elements.forEach(el => {
      this.group.remove(el);
      if (el.material) el.material.dispose();
      if (el.geometry) el.geometry.dispose();
    });
    this.elements = [];
  }
}
