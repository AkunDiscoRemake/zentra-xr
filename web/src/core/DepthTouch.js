/**
 * PINA VR - DepthTouch - Touch Direto 6DOF com detector de profundidade
 * NÃO É TOUCH DE CELULAR - É TOUCH ESPACIAL VIA HAND TRACKING
 * 
 * Como funciona:
 * - Usa posição 3D da ponta do dedo (index tip) + profundidade 6DOF
 * - Raycast contra superfícies 3D (janelas do browser, botões)
 * - Detecta "press" quando Z cruza threshold da superfície + pinch
 * - Feedback háptico visual + auditivo
 */

import * as THREE from 'three';

export class DepthTouch {
  constructor(scene, handTracking) {
    this.scene = scene;
    this.handTracking = handTracking;
    this.raycaster = new THREE.Raycaster();
    this.interactiveObjects = []; // meshes que podem ser tocados
    this.hovered = null;
    this.pressed = null;
    this.touchThreshold = 0.05; // 5cm de profundidade para considerar toque
    this.pinchThreshold = 0.6; // força de pinch para click

    // Visual feedback
    this.touchPointers = [];
    this._createTouchVisuals();

    // Estado de touch por mão
    this.handStates = new Map(); // handIndex -> { isTouching, lastPos, velocity }

    // Listeners
    this.onTouchStart = [];
    this.onTouchEnd = [];
    this.onTouchMove = [];
    this.onClick = [];
  }

  _createTouchVisuals() {
    // Esferas nos dedos para debug + feedback
    for (let i=0;i<2;i++) {
      const geo = new THREE.SphereGeometry(0.015, 16, 16);
      const mat = new THREE.MeshBasicMaterial({ color: 0x00ff88, transparent: true, opacity: 0.8 });
      const mesh = new THREE.Mesh(geo, mat);
      mesh.visible = false;
      mesh.layers.set(1); // visível em ambos olhos
      this.scene.add(mesh);
      this.touchPointers.push(mesh);

      // Ring para indicar profundidade
      const ringGeo = new THREE.RingGeometry(0.02, 0.03, 32);
      const ringMat = new THREE.MeshBasicMaterial({ color: 0x00ff88, side: THREE.DoubleSide, transparent: true, opacity: 0.5 });
      const ring = new THREE.Mesh(ringGeo, ringMat);
      ring.visible = false;
      this.scene.add(ring);
      this.touchPointers.push(ring);
    }

    // Laser pointer para modo distante
    const laserGeo = new THREE.BufferGeometry();
    const laserMat = new THREE.LineBasicMaterial({ color: 0x00ff88, transparent: true, opacity: 0.6 });
    this.laser = new THREE.Line(laserGeo, laserMat);
    this.laser.visible = false;
    this.scene.add(this.laser);
  }

  registerObject(mesh, callbacks = {}) {
    // callbacks: { onTouchStart, onTouchEnd, onClick, onHover }
    mesh.userData.pinaInteractive = true;
    mesh.userData.pinaCallbacks = callbacks;
    this.interactiveObjects.push(mesh);
  }

  unregisterObject(mesh) {
    this.interactiveObjects = this.interactiveObjects.filter(m => m !== mesh);
  }

  update() {
    const hands = this.handTracking.getHands();
    if (!hands || hands.length === 0) {
      this._hidePointers();
      return;
    }

    for (let hi=0; hi<hands.length && hi<2; hi++) {
      const hand = hands[hi];
      const tip = hand.keypoints[8]; // index tip
      const thumbTip = hand.keypoints[4];
      
      // Posição 3D real do dedo em espaço VR
      const fingerPos = new THREE.Vector3(...hand.position);
      // Ajusta para ponta do dedo (offset da palma)
      const tipOffset = this._getTipOffset(hand);
      fingerPos.add(tipOffset);

      // Atualiza visual
      const pointer = this.touchPointers[hi*2];
      if (pointer) {
        pointer.position.copy(fingerPos);
        pointer.visible = true;
        // Cor baseada em pinch
        if (hand.pinch) {
          pointer.material.color.setHex(0xff4444);
          pointer.scale.setScalar(0.7 + hand.pinchStrength*0.5);
        } else {
          pointer.material.color.setHex(0x00ff88);
          pointer.scale.setScalar(1);
        }
      }

      // Raycast da posição do dedo + direção (frente da mão)
      const dir = new THREE.Vector3(0,0,-1);
      // Rotação da mão
      const euler = new THREE.Euler(hand.rotation.pitch, hand.rotation.yaw, hand.rotation.roll, 'YXZ');
      dir.applyEuler(euler);

      this.raycaster.set(fingerPos, dir);
      this.raycaster.near = 0;
      this.raycaster.far = 3;

      const intersects = this.raycaster.intersectObjects(this.interactiveObjects, false);

      // Laser para feedback
      if (hi===0) {
        if (intersects.length > 0) {
          const hit = intersects[0];
          const points = [fingerPos, hit.point];
          this.laser.geometry.setFromPoints(points);
          this.laser.visible = true;
        } else {
          const farPoint = fingerPos.clone().add(dir.clone().multiplyScalar(3));
          this.laser.geometry.setFromPoints([fingerPos, farPoint]);
          this.laser.visible = true;
        }
      }

      // Lógica de touch 6DOF: depth detection
      const state = this.handStates.get(hi) || { isTouching: false, lastPos: fingerPos.clone(), depth: hand.depth };

      if (intersects.length > 0) {
        const hit = intersects[0];
        const distance = hit.distance; // distância até superfície
        const object = hit.object;

        // Depth touch: se dedo está a < threshold da superfície E pinch ou mão avançando
        const isClose = distance < this.touchThreshold;
        const isPinching = hand.pinch && hand.pinchStrength > this.pinchThreshold;
        const isGrabTouch = hand.grab && hand.grabStrength > 0.7;

        // Velocidade do dedo (para detectar tap rápido)
        const velocity = fingerPos.clone().sub(state.lastPos).length() / 0.016; // m/s aproximado
        const isFastApproach = velocity > 0.5 && dir.dot(hit.point.clone().sub(fingerPos).normalize()) > 0.5;

        const shouldTouch = isClose && (isPinching || isGrabTouch || isFastApproach);

        // Hover
        if (this.hovered !== object) {
          if (this.hovered && this.hovered.userData.pinaCallbacks?.onHoverEnd) {
            this.hovered.userData.pinaCallbacks.onHoverEnd({ hand, point: hit.point });
          }
          this.hovered = object;
          if (object.userData.pinaCallbacks?.onHover) {
            object.userData.pinaCallbacks.onHover({ hand, point: hit.point, distance });
          }
        }

        // Touch start
        if (shouldTouch && !state.isTouching) {
          state.isTouching = true;
          this.pressed = object;
          this._trigger(object, 'onTouchStart', { hand, point: hit.point, distance, fingerPos });
          this.onTouchStart.forEach(cb => cb({ object, hand, point: hit.point }));
          // Feedback visual: pulso
          this._pulsePointer(hi);
        } 
        // Touch move
        else if (state.isTouching && this.pressed === object) {
          this._trigger(object, 'onTouchMove', { hand, point: hit.point, fingerPos });
          this.onTouchMove.forEach(cb => cb({ object, hand, point: hit.point }));
        }

        // Click detection: soltar pinch próximo à superfície
        if (state.isTouching && !shouldTouch) {
          // Foi um click
          this._trigger(object, 'onClick', { hand, point: hit.point, fingerPos });
          this.onClick.forEach(cb => cb({ object, hand, point: hit.point }));
          this._trigger(object, 'onTouchEnd', { hand, point: hit.point });
          this.onTouchEnd.forEach(cb => cb({ object, hand, point: hit.point }));
          state.isTouching = false;
          this.pressed = null;
        }

      } else {
        // Sem interseção
        if (this.hovered) {
          if (this.hovered.userData.pinaCallbacks?.onHoverEnd) {
            this.hovered.userData.pinaCallbacks.onHoverEnd({ hand });
          }
          this.hovered = null;
        }
        if (state.isTouching) {
          if (this.pressed && this.pressed.userData.pinaCallbacks?.onTouchEnd) {
            this.pressed.userData.pinaCallbacks.onTouchEnd({ hand });
          }
          state.isTouching = false;
          this.pressed = null;
        }
      }

      state.lastPos = fingerPos.clone();
      state.depth = hand.depth;
      this.handStates.set(hi, state);
    }
  }

  _getTipOffset(hand) {
    // Offset da palma até ponta do index baseado na orientação
    // Aproximado: 10cm à frente da palma
    const dir = new THREE.Vector3(0,0,-0.1);
    const euler = new THREE.Euler(hand.rotation.pitch, hand.rotation.yaw, hand.rotation.roll, 'YXZ');
    dir.applyEuler(euler);
    return dir;
  }

  _trigger(object, eventName, data) {
    if (object.userData.pinaCallbacks?.[eventName]) {
      object.userData.pinaCallbacks[eventName](data);
    }
  }

  _pulsePointer(handIndex) {
    const pointer = this.touchPointers[handIndex*2];
    if (!pointer) return;
    const originalScale = pointer.scale.x;
    pointer.scale.setScalar(originalScale*1.8);
    setTimeout(() => { if (pointer) pointer.scale.setScalar(originalScale); }, 150);
  }

  _hidePointers() {
    this.touchPointers.forEach(p => p.visible = false);
    this.laser.visible = false;
  }

  // API pública para devs
  on(event, cb) {
    if (event === 'touchstart') this.onTouchStart.push(cb);
    if (event === 'touchend') this.onTouchEnd.push(cb);
    if (event === 'touchmove') this.onTouchMove.push(cb);
    if (event === 'click') this.onClick.push(cb);
  }
}
