/**
 * PINA VR - Pina Saber - Beat Saber Clone Completo
 * - Sabres presos às mãos via hand tracking
 * - Cubos vindo em ritmo
 * - Corte direcional (setas)
 * - Partículas, haptics visuais, score
 * - 100% 3D spatial
 */

import * as THREE from 'three';

export class PinaSaber {
  constructor(scene, handTracking, spatialUI, depthTouch) {
    this.scene = scene;
    this.handTracking = handTracking;
    this.spatialUI = spatialUI;
    this.depthTouch = depthTouch;

    this.isPlaying = false;
    this.score = 0;
    this.combo = 0;
    this.maxCombo = 0;
    this.health = 100;

    this.sabers = [];
    this.cubes = [];
    this.particles = [];

    this.trackGroup = new THREE.Group();
    this.scene.add(this.trackGroup);

    this.speed = 3; // m/s
    this.spawnInterval = 0.8;
    this.lastSpawn = 0;
    this.songTime = 0;
    this.beatMap = this._generateBeatMap();

    this._createEnvironment();
    this._createSabers();
  }

  _createEnvironment() {
    // Pista estilo Beat Saber
    const trackGeo = new THREE.PlaneGeometry(4, 50);
    const trackMat = new THREE.MeshStandardMaterial({ color: 0x0a0a0a, metalness: 0.8, roughness: 0.2 });
    const track = new THREE.Mesh(trackGeo, trackMat);
    track.rotation.x = -Math.PI/2;
    track.position.set(0, 0, -15);
    this.trackGroup.add(track);

    // Laterais neon
    const sideGeo = new THREE.BoxGeometry(0.1, 0.5, 50);
    const leftMat = new THREE.MeshBasicMaterial({ color: 0x00ffff });
    const rightMat = new THREE.MeshBasicMaterial({ color: 0xff00ff });
    const left = new THREE.Mesh(sideGeo, leftMat);
    left.position.set(-2, 0.25, -15);
    const right = new THREE.Mesh(sideGeo, rightMat);
    right.position.set(2, 0.25, -15);
    this.trackGroup.add(left, right);

    // Grid no chão
    const grid = new THREE.GridHelper(20, 20, 0x00ff88, 0x222222);
    grid.position.set(0, 0.01, -15);
    this.trackGroup.add(grid);
  }

  _createSabers() {
    // Dois sabres: esquerda e direita, presos às mãos
    for (let i=0;i<2;i++) {
      const saberGroup = new THREE.Group();

      // Cabo
      const handleGeo = new THREE.CylinderGeometry(0.03, 0.04, 0.3, 16);
      const handleMat = new THREE.MeshStandardMaterial({ color: 0x222222, metalness: 0.9, roughness: 0.1 });
      const handle = new THREE.Mesh(handleGeo, handleMat);
      handle.rotation.x = Math.PI/2;
      saberGroup.add(handle);

      // Lâmina
      const bladeGeo = new THREE.CylinderGeometry(0.015, 0.015, 1.2, 16);
      const color = i===0 ? 0x00ffff : 0xff0066;
      const bladeMat = new THREE.MeshBasicMaterial({ 
        color, 
        transparent: true, 
        opacity: 0.9 
      });
      const blade = new THREE.Mesh(bladeGeo, bladeMat);
      blade.position.set(0, 0, -0.75);
      blade.rotation.x = Math.PI/2;
      saberGroup.add(blade);

      // Glow
      const glowGeo = new THREE.CylinderGeometry(0.04, 0.04, 1.2, 16);
      const glowMat = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.3 });
      const glow = new THREE.Mesh(glowGeo, glowMat);
      glow.position.copy(blade.position);
      glow.rotation.copy(blade.rotation);
      saberGroup.add(glow);

      // Trail
      const trailGeo = new THREE.BufferGeometry();
      const trailMat = new THREE.LineBasicMaterial({ color, transparent: true, opacity: 0.6 });
      const trail = new THREE.Line(trailGeo, trailMat);
      saberGroup.add(trail);

      saberGroup.visible = false;
      saberGroup.userData = { color, index: i, blade, glow, trail, trailPoints: [] };
      this.scene.add(saberGroup);
      this.sabers.push(saberGroup);
    }
  }

  _generateBeatMap() {
    // Mapa simples procedural, 60 beats
    const map = [];
    for (let i=0;i<80;i++) {
      const time = i * 0.8 + Math.random()*0.3;
      const lane = Math.floor(Math.random()*4) - 1.5; // -1.5 a 1.5
      const layer = Math.floor(Math.random()*3); // 0,1,2 altura
      const color = Math.random() > 0.5 ? 0 : 1; // 0=esq, 1=dir
      const direction = Math.floor(Math.random()*8); // 0-7 direções de corte
      map.push({ time, lane, layer, color, direction, hit: false });
    }
    return map.sort((a,b)=>a.time-b.time);
  }

  start() {
    if (this.isPlaying) return;
    this.isPlaying = true;
    this.score = 0;
    this.combo = 0;
    this.health = 100;
    this.songTime = 0;
    this.cubes.forEach(c => this.trackGroup.remove(c.mesh));
    this.cubes = [];
    console.log('[Pina Saber] START - Beat Saber Clone');

    // UI de score 3D
    this._createScoreUI();
  }

  stop() {
    this.isPlaying = false;
    this.sabers.forEach(s => s.visible = false);
    this.cubes.forEach(c => this.trackGroup.remove(c.mesh));
    this.cubes = [];
    if (this.scoreUI) this.scene.remove(this.scoreUI);
  }

  _createScoreUI() {
    if (this.scoreUI) this.scene.remove(this.scoreUI);
    const canvas = document.createElement('canvas');
    canvas.width = 512; canvas.height = 256;
    this.scoreCanvas = canvas;
    this.scoreCtx = canvas.getContext('2d');
    this._updateScoreCanvas();

    const tex = new THREE.CanvasTexture(canvas);
    const geo = new THREE.PlaneGeometry(1.5, 0.75);
    const mat = new THREE.MeshBasicMaterial({ map: tex, transparent: true, side: THREE.DoubleSide });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set(0, 2.2, -2);
    this.scene.add(mesh);
    this.scoreUI = mesh;
    this.scoreTex = tex;
  }

  _updateScoreCanvas() {
    const ctx = this.scoreCtx;
    ctx.clearRect(0,0,512,256);
    ctx.fillStyle = 'rgba(0,0,0,0.7)';
    ctx.fillRect(0,0,512,256);
    ctx.fillStyle = '#00ff88';
    ctx.font = 'bold 48px monospace';
    ctx.fillText(`SCORE: ${this.score}`, 20, 70);
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 32px monospace';
    ctx.fillText(`COMBO: ${this.combo}x`, 20, 120);
    ctx.fillStyle = '#ff0066';
    ctx.fillRect(20, 150, this.health*2, 20);
    if (this.scoreTex) this.scoreTex.needsUpdate = true;
  }

  update(dt, time) {
    if (!this.isPlaying) {
      // Atualiza posição dos sabres mesmo fora do jogo para preview
      this._updateSabersFromHands();
      return;
    }

    this.songTime += dt;
    this._updateSabersFromHands();
    this._spawnCubes();
    this._updateCubes(dt);
    this._checkCollisions();
    this._updateParticles(dt);
  }

  _updateSabersFromHands() {
    const hands = this.handTracking.getHands();
    for (let i=0;i<Math.min(hands.length,2);i++) {
      const hand = hands[i];
      const saber = this.sabers[i];
      if (!saber) continue;

      saber.visible = true;
      saber.position.set(...hand.position);
      // Rotação: sabre aponta para frente da mão
      const euler = new THREE.Euler(hand.rotation.pitch, hand.rotation.yaw, hand.rotation.roll, 'YXZ');
      saber.quaternion.setFromEuler(euler);
      // Ajuste: sabre para frente
      saber.rotateX(-Math.PI/2);

      // Trail
      const trailPoints = saber.userData.trailPoints;
      trailPoints.push(saber.position.clone());
      if (trailPoints.length > 15) trailPoints.shift();
      if (trailPoints.length > 1) {
        saber.userData.trail.geometry.setFromPoints(trailPoints);
      }

      // Scale com grab
      const scale = hand.grab ? 1.2 : 1.0;
      saber.scale.setScalar(scale);
    }

    // Se só 1 mão, esconde outro sabre
    if (hands.length === 1) {
      this.sabers[1].visible = false;
    } else if (hands.length === 0) {
      this.sabers.forEach(s => s.visible = false);
    }
  }

  _spawnCubes() {
    while (this.beatMap.length > 0 && this.beatMap[0].time <= this.songTime) {
      const beat = this.beatMap.shift();
      this._createCube(beat);
    }
  }

  _createCube(beat) {
    const size = 0.5;
    const geo = new THREE.BoxGeometry(size, size, size);
    const color = beat.color === 0 ? 0x00ffff : 0xff0066;
    const mat = new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.5 });
    const mesh = new THREE.Mesh(geo, mat);
    
    const x = beat.lane;
    const y = 0.5 + beat.layer * 0.6;
    const z = -20; // longe
    mesh.position.set(x, y, z);
    mesh.userData = { beat, color, velocity: new THREE.Vector3(0,0,this.speed), cut: false };

    // Seta de direção
    const arrowCanvas = document.createElement('canvas');
    arrowCanvas.width = 128; arrowCanvas.height = 128;
    const ctx = arrowCanvas.getContext('2d');
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 80px monospace';
    const arrows = ['↑','↗','→','↘','↓','↙','←','↖'];
    ctx.textAlign = 'center';
    ctx.fillText(arrows[beat.direction] || '•', 64, 80);
    const arrowTex = new THREE.CanvasTexture(arrowCanvas);
    const arrowGeo = new THREE.PlaneGeometry(0.4,0.4);
    const arrowMat = new THREE.MeshBasicMaterial({ map: arrowTex, transparent: true });
    const arrow = new THREE.Mesh(arrowGeo, arrowMat);
    arrow.position.z = 0.26;
    mesh.add(arrow);

    this.trackGroup.add(mesh);
    this.cubes.push({ mesh, beat, spawnTime: this.songTime });
  }

  _updateCubes(dt) {
    for (let i=this.cubes.length-1; i>=0; i--) {
      const c = this.cubes[i];
      c.mesh.position.z += this.speed * dt;
      c.mesh.rotation.y += dt * 0.5;

      // Remove se passou
      if (c.mesh.position.z > 1) {
        this.trackGroup.remove(c.mesh);
        this.cubes.splice(i,1);
        this.combo = 0;
        this.health -= 10;
        this._updateScoreCanvas();
        if (this.health <= 0) {
          console.log('[Pina Saber] GAME OVER');
          this.stop();
        }
      }
    }
  }

  _checkCollisions() {
    for (let i=this.cubes.length-1; i>=0; i--) {
      const c = this.cubes[i];
      if (c.mesh.userData.cut) continue;

      for (let s=0; s<this.sabers.length; s++) {
        const saber = this.sabers[s];
        if (!saber.visible) continue;
        const saberColor = saber.userData.color;
        const cubeColor = c.mesh.userData.color;
        // Em Beat Saber, pode cortar qualquer cor, mas bonus se cor certa
        const dist = saber.position.distanceTo(c.mesh.position);
        if (dist < 0.7) {
          // Verifica velocidade de corte (precisa estar se movendo)
          const trail = saber.userData.trailPoints;
          if (trail.length >= 2) {
            const last = trail[trail.length-1];
            const prev = trail[trail.length-2];
            const vel = last.distanceTo(prev) / 0.016;
            if (vel > 0.5) {
              this._cutCube(i, s, vel);
              break;
            }
          }
        }
      }
    }
  }

  _cutCube(cubeIndex, saberIndex, velocity) {
    const c = this.cubes[cubeIndex];
    c.mesh.userData.cut = true;

    // Score baseado em velocidade e ângulo
    const points = Math.floor(50 + velocity*20 + this.combo*5);
    this.score += points;
    this.combo++;
    this.maxCombo = Math.max(this.maxCombo, this.combo);
    this.health = Math.min(100, this.health + 2);

    // Partículas
    this._spawnCutParticles(c.mesh.position.clone(), c.mesh.userData.color);

    // Divide cubo em dois
    const leftGeo = new THREE.BoxGeometry(0.25, 0.5, 0.5);
    const rightGeo = new THREE.BoxGeometry(0.25, 0.5, 0.5);
    const mat = c.mesh.material.clone();
    const left = new THREE.Mesh(leftGeo, mat);
    const right = new THREE.Mesh(rightGeo, mat);
    left.position.copy(c.mesh.position);
    left.position.x -= 0.15;
    right.position.copy(c.mesh.position);
    right.position.x += 0.15;
    left.userData.velocity = new THREE.Vector3(-1, 1, 1);
    right.userData.velocity = new THREE.Vector3(1, 1, 1);
    this.trackGroup.add(left, right);
    this.particles.push({ mesh: left, life: 1.0 }, { mesh: right, life: 1.0 });

    // Remove original
    this.trackGroup.remove(c.mesh);
    this.cubes.splice(cubeIndex, 1);

    this._updateScoreCanvas();

    // Feedback visual no sabre
    const saber = this.sabers[saberIndex];
    saber.userData.glow.material.opacity = 1.0;
    setTimeout(()=> { if (saber.userData.glow) saber.userData.glow.material.opacity = 0.3; }, 100);
  }

  _spawnCutParticles(pos, color) {
    for (let i=0;i<12;i++) {
      const geo = new THREE.SphereGeometry(0.03, 6,6);
      const mat = new THREE.MeshBasicMaterial({ color });
      const mesh = new THREE.Mesh(geo, mat);
      mesh.position.copy(pos);
      mesh.userData.velocity = new THREE.Vector3(
        (Math.random()-0.5)*3,
        Math.random()*3,
        (Math.random()-0.5)*3
      );
      this.trackGroup.add(mesh);
      this.particles.push({ mesh, life: 0.8 });
    }
  }

  _updateParticles(dt) {
    for (let i=this.particles.length-1; i>=0; i--) {
      const p = this.particles[i];
      p.mesh.position.add(p.mesh.userData.velocity.clone().multiplyScalar(dt));
      p.mesh.userData.velocity.y -= 4*dt; // gravidade
      p.life -= dt;
      p.mesh.material.transparent = true;
      p.mesh.material.opacity = p.life;
      if (p.life <= 0) {
        this.trackGroup.remove(p.mesh);
        this.particles.splice(i,1);
      }
    }
  }

  getScore() { return { score: this.score, combo: this.combo, maxCombo: this.maxCombo, health: this.health }; }
}
