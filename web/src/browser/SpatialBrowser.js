/**
 * PINA VR - Spatial Browser - Navegador 3D Multitarefa
 * - Até 5 janelas curvas em arco 180°
 * - Atalhos Discord, YouTube totalmente no navegador espacial
 * - Multitarefa espacial: arrastar janelas com pinch
 */

import { BrowserWindow } from './BrowserWindow.js';

export class SpatialBrowser {
  constructor(scene, cssScene, depthTouch, spatialUI) {
    this.scene = scene;
    this.cssScene = cssScene;
    this.depthTouch = depthTouch;
    this.spatialUI = spatialUI;
    this.windows = [];
    this.maxWindows = 5;
    this.nextId = 1;

    // Atalhos pré-definidos (totalmente no navegador)
    this.shortcuts = [
      { id: 'youtube', label: 'YouTube', url: 'https://m.youtube.com', icon: '▶', color: 0xff0000, position: [-1.2, 1.2, -1.8] },
      { id: 'discord', label: 'Discord', url: 'https://discord.com/app', icon: '◈', color: 0x5865f2, position: [1.2, 1.2, -1.8] },
      { id: 'google', label: 'Google', url: 'https://google.com', icon: '◉', color: 0x4285f4, position: [0, 1.2, -2.2] },
      { id: 'twitch', label: 'Twitch', url: 'https://m.twitch.tv', icon: '⬢', color: 0x9146ff, position: [-0.6, 0.4, -1.6] },
      { id: 'github', label: 'GitHub', url: 'https://github.com', icon: '⬣', color: 0xffffff, position: [0.6, 0.4, -1.6] }
    ];

    this._createShortcutPanel();
  }

  _createShortcutPanel() {
    // Painel de atalhos flutuante 3D
    const panelPos = [0, 0.3, -1.5];
    this.shortcutPanel = this.spatialUI.createPanel({ position: panelPos, size: [1.8, 0.6, 0.02], title: 'PINA // SHORTCUTS' });

    // Botões de atalho
    this.shortcuts.forEach((sc, i) => {
      const x = (i - 2) * 0.32;
      const btn = this.spatialUI.createButton({
        label: `${sc.icon} ${sc.label}`,
        position: [panelPos[0] + x, panelPos[1] - 0.1, panelPos[2] + 0.02],
        size: [0.28, 0.1, 0.02],
        color: sc.color,
        onClick: () => this.open(sc.url, sc.label)
      });
      btn.userData.shortcut = sc;
    });

    // Botão nova janela vazia
    this.spatialUI.createButton({
      label: '+ NOVA JANELA',
      position: [panelPos[0], panelPos[1] - 0.25, panelPos[2] + 0.02],
      size: [0.5, 0.08, 0.02],
      color: 0x00ff88,
      onClick: () => this.open('https://google.com', 'Nova Janela')
    });
  }

  open(url, title = 'Browser') {
    if (this.windows.length >= this.maxWindows) {
      console.warn('[Pina Browser] Limite de janelas atingido, fechando mais antiga');
      this.close(this.windows[0].id);
    }

    // Calcula posição em arco
    const count = this.windows.length;
    const angle = (count - 1) * 25 * Math.PI/180; // 25° por janela
    const radius = 2.2;
    const x = Math.sin(angle) * radius;
    const z = -Math.cos(angle) * radius - 0.2;
    const y = 1.5 + (count % 2) * 0.15; // leve variação Y

    const win = new BrowserWindow({
      id: this.nextId++,
      url,
      title,
      position: [x, y, z],
      width: 1280,
      height: 800,
      curved: true
    }, this.scene, this.cssScene, this.depthTouch);

    this.windows.forEach(w => w.blur());
    win.focus();
    this.windows.push(win);

    console.log(`[Pina Browser] Aberto ${url} - total ${this.windows.length}`);
    return win;
  }

  close(id) {
    const idx = this.windows.findIndex(w => w.id === id);
    if (idx === -1) return;
    const win = this.windows[idx];
    win.destroy();
    this.windows.splice(idx, 1);
    this._rearrange();
  }

  _rearrange() {
    // Reorganiza janelas em arco quando fecha uma
    this.windows.forEach((win, i) => {
      const angle = (i - (this.windows.length-1)/2) * 30 * Math.PI/180;
      const radius = 2.2;
      const x = Math.sin(angle) * radius;
      const z = -Math.cos(angle) * radius - 0.2;
      const y = 1.5;
      win.setPosition([x, y, z]);
    });
  }

  focus(id) {
    this.windows.forEach(w => {
      if (w.id === id) w.focus();
      else w.blur();
    });
  }

  getWindows() { return this.windows; }

  // API para devs
  createWindow(opts) {
    return this.open(opts.url || 'https://google.com', opts.title || 'App');
  }

  // Atalhos rápidos
  openYouTube() { return this.open('https://m.youtube.com', 'YouTube'); }
  openDiscord() { return this.open('https://discord.com/app', 'Discord'); }
}
