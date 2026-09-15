/**
 * PINA VR - BrowserWindow - Janela 3D individual do navegador espacial
 * Usa CSS3DRenderer para conteúdo web real + WebGL para moldura
 */

import * as THREE from 'three';
import { CSS3DObject } from 'three/addons/renderers/CSS3DRenderer.js';

export class BrowserWindow {
  constructor({ id, url = 'https://google.com', position = [0,1.5,-2], width = 1280, height = 800, scale = 0.0015, curved = true, title = 'Browser' }, scene, cssScene, depthTouch) {
    this.id = id;
    this.url = url;
    this.position = position;
    this.width = width;
    this.height = height;
    this.scale = scale;
    this.curved = curved;
    this.title = title;
    this.scene = scene;
    this.cssScene = cssScene;
    this.depthTouch = depthTouch;

    this.isFocused = false;
    this.isMinimized = false;

    this._createElements();
  }

  _createElements() {
    // Elemento DOM real (iframe)
    this.domElement = document.createElement('div');
    this.domElement.style.width = this.width + 'px';
    this.domElement.style.height = this.height + 'px';
    this.domElement.style.background = '#1a1a2e';
    this.domElement.style.borderRadius = '12px';
    this.domElement.style.overflow = 'hidden';
    this.domElement.style.boxShadow = '0 0 40px rgba(0,255,136,0.3)';
    this.domElement.style.display = 'flex';
    this.domElement.style.flexDirection = 'column';

    // Barra de título 3D (parte do DOM mas estilizada como espacial)
    const titleBar = document.createElement('div');
    titleBar.style.height = '48px';
    titleBar.style.background = 'linear-gradient(90deg, #0f0f1e, #1a1a2e)';
    titleBar.style.display = 'flex';
    titleBar.style.alignItems = 'center';
    titleBar.style.padding = '0 16px';
    titleBar.style.color = '#00ff88';
    titleBar.style.fontFamily = 'monospace';
    titleBar.style.fontSize = '18px';
    titleBar.style.justifyContent = 'space-between';

    const titleText = document.createElement('span');
    titleText.textContent = `◉ ${this.title} — ${this.url}`;
    titleBar.appendChild(titleText);

    const controls = document.createElement('div');
    controls.style.display = 'flex';
    controls.style.gap = '8px';
    ['—', '□', '✕'].forEach(sym => {
      const btn = document.createElement('span');
      btn.textContent = sym;
      btn.style.cursor = 'pointer';
      btn.style.padding = '4px 8px';
      btn.style.background = '#2a2a4e';
      btn.style.borderRadius = '4px';
      controls.appendChild(btn);
    });
    titleBar.appendChild(controls);

    this.domElement.appendChild(titleBar);

    // Iframe
    this.iframe = document.createElement('iframe');
    this.iframe.src = this.url;
    this.iframe.style.flex = '1';
    this.iframe.style.border = 'none';
    this.iframe.style.width = '100%';
    this.iframe.style.background = '#fff';
    this.iframe.setAttribute('allow', 'fullscreen; autoplay; encrypted-media; camera; microphone');
    this.iframe.setAttribute('sandbox', 'allow-same-origin allow-scripts allow-forms allow-popups allow-downloads allow-presentation');
    this.domElement.appendChild(this.iframe);

    // CSS3DObject
    this.cssObject = new CSS3DObject(this.domElement);
    this.cssObject.position.set(...this.position);
    this.cssObject.scale.set(this.scale, this.scale, this.scale);
    // Faz olhar para centro
    this.cssObject.lookAt(0, this.position[1], 0);
    this.cssScene.add(this.cssObject);

    // Mesh WebGL invisível para raycast / depth touch (mesmo tamanho)
    const w = this.width * this.scale;
    const h = this.height * this.scale;

    let geo;
    if (this.curved) {
      // Curva cilíndrica
      const radius = 2.5;
      const arc = w / radius;
      geo = new THREE.CylinderGeometry(radius, radius, h, 32, 1, true, -arc/2, arc);
      // Ajusta para ficar plano ao olhar
    } else {
      geo = new THREE.PlaneGeometry(w, h);
    }

    const mat = new THREE.MeshBasicMaterial({ 
      color: 0x000000, 
      transparent: true, 
      opacity: 0.01, // quase invisível mas recebe raycast
      side: THREE.DoubleSide 
    });
    this.hitMesh = new THREE.Mesh(geo, mat);
    this.hitMesh.position.set(...this.position);
    this.hitMesh.lookAt(0, this.position[1], 0);
    if (this.curved) this.hitMesh.rotation.y += Math.PI;
    this.scene.add(this.hitMesh);

    // Moldura 3D brilhante
    const frameGeo = new THREE.BoxGeometry(w+0.02, h+0.02, 0.01);
    const frameMat = new THREE.MeshBasicMaterial({ 
      color: 0x00ff88, 
      transparent: true, 
      opacity: 0.15,
      wireframe: false
    });
    this.frame = new THREE.Mesh(frameGeo, frameMat);
    this.frame.position.copy(this.hitMesh.position);
    this.frame.quaternion.copy(this.hitMesh.quaternion);
    this.scene.add(this.frame);

    // Registra para depth touch
    this.depthTouch.registerObject(this.hitMesh, {
      onClick: (data) => {
        this.focus();
        // Converte ponto 3D para coordenada UV do iframe e simula click
        this._handle3DClick(data);
      },
      onHover: () => {
        this.frame.material.opacity = 0.4;
      },
      onHoverEnd: () => {
        if (!this.isFocused) this.frame.material.opacity = 0.15;
      }
    });

    console.log(`[Pina Browser] Janela ${this.id} criada: ${this.url} em`, this.position);
  }

  _handle3DClick(data) {
    // Calcula UV do hit
    if (!data.point) return;
    // Simplificado: foca iframe
    this.iframe.focus();
    // Efeito visual de click
    this.frame.material.color.setHex(0xffffff);
    setTimeout(() => this.frame.material.color.setHex(0x00ff88), 150);
  }

  setUrl(newUrl) {
    this.url = newUrl;
    this.iframe.src = newUrl;
    this.domElement.querySelector('span').textContent = `◉ ${this.title} — ${newUrl}`;
  }

  focus() {
    this.isFocused = true;
    this.frame.material.opacity = 0.6;
    this.frame.material.color.setHex(0x00ff88);
    this.domElement.style.boxShadow = '0 0 60px rgba(0,255,136,0.6)';
    this.cssObject.scale.set(this.scale*1.05, this.scale*1.05, this.scale*1.05);
  }

  blur() {
    this.isFocused = false;
    this.frame.material.opacity = 0.15;
    this.domElement.style.boxShadow = '0 0 40px rgba(0,255,136,0.3)';
    this.cssObject.scale.set(this.scale, this.scale, this.scale);
  }

  setPosition(pos) {
    this.position = pos;
    this.cssObject.position.set(...pos);
    this.hitMesh.position.set(...pos);
    this.frame.position.set(...pos);
    this.cssObject.lookAt(0, pos[1], 0);
    this.hitMesh.lookAt(0, pos[1], 0);
    this.frame.lookAt(0, pos[1], 0);
  }

  destroy() {
    this.cssScene.remove(this.cssObject);
    this.scene.remove(this.hitMesh);
    this.scene.remove(this.frame);
    this.depthTouch.unregisterObject(this.hitMesh);
    if (this.domElement.parentNode) this.domElement.parentNode.removeChild(this.domElement);
  }

  minimize() {
    this.isMinimized = true;
    this.cssObject.visible = false;
    this.hitMesh.visible = false;
    this.frame.visible = false;
  }

  restore() {
    this.isMinimized = false;
    this.cssObject.visible = true;
    this.hitMesh.visible = true;
    this.frame.visible = true;
  }
}
