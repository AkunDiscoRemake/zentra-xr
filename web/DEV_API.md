# PINA VR - Dev API - Android

API completa para criar apps espaciais dentro do PINA VR OS.

## Como usar

No seu app web que roda dentro do PINA VR (via Spatial Browser ou nativo):

```js
// Espera API pronta
window.addEventListener('pina-ready', () => {
  console.log(PinaVR.version); // 1.0.0-pina
});

 // ou direto se já carregou
if (window.PinaVR) { /* ... */ }
```

## window.PinaVR

### Sensores - Melhor Giroscópio Possível

```js
// Fusão gyro + accel + mag com Kalman + Madgwick + Complementary
PinaVR.sensors.onOrientation((quat, accel, gyro, euler) => {
  // quat: [x,y,z,w]
  // accel: [x,y,z] m/s²
  // gyro: [x,y,z] rad/s
  // euler: {x,y,z} rad
  console.log(quat);
});

const q = PinaVR.sensors.getQuaternion();
const e = PinaVR.sensors.getEuler();
PinaVR.sensors.reset();
console.log(PinaVR.sensors.mode()); // 'web' | 'native' | 'native-abs'
```

Detalhe da fusão:
- **Web**: Generic Sensor API 60Hz + DeviceOrientation + Kalman + Madgwick
- **Nativo Android**: RotationVector 200Hz + Gyro Uncalibrated + bias auto-calibration + OneEuro
- Interpolação preditiva 1000Hz com predição de 20ms para reduzir motion-to-photon latency
- Drift correction via accel + mag

### Hand Tracking - One Euro + Kalman

```js
PinaVR.hands.onHands((hands) => {
  // hands: array de até 2 mãos
  hands.forEach(hand => {
    console.log(hand.handedness); // 'Left' | 'Right'
    console.log(hand.position); // [x,y,z] em metros no espaço VR (6DOF)
    console.log(hand.rotation); // {yaw, pitch, roll}
    console.log(hand.depth); // metros (0.1 a 1.5)
    console.log(hand.keypoints); // 21 pontos filtrados {x,y,z}
    console.log(hand.pinch, hand.pinchStrength); // bool + 0-1
    console.log(hand.grab, hand.grabStrength);
    console.log(hand.point, hand.palmOpen);
    console.log(hand.predicted); // predição Kalman 30ms à frente
  });
});

const hands = PinaVR.hands.getHands();
```

Filtros:
- **OneEuroFilter**: minCutoff 1.5, beta 0.008, dCutoff 1.2 para landmarks, 0.7 para wrist (mais estável)
- **Kalman3DVelocity**: Q=0.005, R=0.05 para pontos críticos (wrist, tips, MCPs)
- Depth 6DOF: `depth = (realHandSize * focal) / imageSize`, clamp 0.1-1.5m

### Touch Direto 6DOF - NÃO É TOUCH DE CELULAR

```js
// Registra qualquer mesh Three.js como tocável
const mesh = new THREE.Mesh(geo, mat);
PinaVR.touch.registerObject(mesh, {
  onHover: ({hand, point, distance}) => {},
  onHoverEnd: () => {},
  onTouchStart: ({hand, point, fingerPos}) => {},
  onTouchMove: () => {},
  onClick: ({hand, point}) => { console.log('click 6DOF!'); }
});

// Threshold de profundidade para considerar toque (default 5cm)
PinaVR.touch.threshold(0.05); // 5cm
PinaVR.touch.threshold(); // getter

// Eventos globais
PinaVR.touch.on('click', ({object, hand, point}) => {});
PinaVR.touch.on('touchstart', ...);
```

Como funciona:
1. Pega posição 3D da ponta do index (hand.position + offset)
2. Raycast com direção da mão
3. Se distância < threshold (5cm) + pinch > 0.6 OU grab > 0.7 OU approach rápido > 0.5m/s, considera toque
4. Dispara onClick ao soltar pinch próximo à superfície

### UI 100% 3D Spatial

```js
// Botão 3D (mesh com shader SDF + texto canvas, não DOM 2D)
const btn = PinaVR.ui.createButton({
  label: 'PLAY',
  position: [0,1.5,-1.5],
  size: [0.4,0.12,0.02],
  color: 0x1a1a2e,
  onClick: () => console.log('clicado via hand tracking 6DOF'),
  onTouch: () => {}
});

// Painel 3D
const panel = PinaVR.ui.createPanel({
  position: [0,1.5,-2],
  size: [1.2,0.8,0.02],
  title: 'MEU APP'
});

// Slider 3D
const slider = PinaVR.ui.createSlider({
  position: [0,1,-1],
  min: 0, max: 1, value: 0.5,
  onChange: (v) => {}
});
```

### Browser Espacial 3D + Multitarefa

```js
// Cria janela 3D curva com conteúdo web real
const win = PinaVR.browser.createWindow({
  url: 'https://discord.com/app',
  title: 'Discord',
  position: [0,1.5,-2],
  curved: true
});

PinaVR.browser.open('https://m.youtube.com', 'YouTube');
PinaVR.browser.openYouTube(); // atalho
PinaVR.browser.openDiscord(); // atalho

PinaVR.browser.close(id);
PinaVR.browser.getWindows(); // lista de janelas
console.log(PinaVR.browser.shortcuts); // atalhos pré-definidos
```

Janelas:
- CSS3DRenderer para conteúdo real (iframe)
- Hit mesh invisível para raycast 6DOF
- Curvatura cilíndrica 120° FOV
- Até 5 janelas em arco 180°
- Multitarefa: arrasta com pinch (futuro: implementado via setPosition)

### XR - Cardboard

```js
PinaVR.xr.setIPD(0.064); // 64mm default, range 50-80mm
const eyes = PinaVR.xr.getEyeMatrices(); // {left, right} com position, quaternion, projection
PinaVR.xr.resize(width, height);
```

Renderização:
- Stereo manual com 2 câmeras + IPD
- Barrel distortion shader k1=0.2, k2=0.05 para lentes Cardboard
- Render targets 1024x1024 com MSAA 2x
- Suporte WebXR opcional mas Cardboard é primary

### Mixed Reality

```js
PinaVR.mr.setMode('mixed'); // default - passthrough câmera traseira
PinaVR.mr.setMode('vr'); // void escuro com skybox
console.log(PinaVR.mr.getMode());
console.log(PinaVR.mr.isActive());
```

MR:
- Câmera traseira 1920x1080 60fps mapeada em esfera invertida 50m
- Shader com vignette + grid sutil para depth cue
- Distorção invertida para Cardboard

### Jogos - Pina Saber (Beat Saber Clone)

```js
PinaVR.games.pinaSaber.start();
PinaVR.games.pinaSaber.stop();
console.log(PinaVR.games.pinaSaber.getScore()); // {score, combo, maxCombo, health}
console.log(PinaVR.games.pinaSaber.isPlaying());
```

Gameplay:
- Sabres presos às mãos via hand tracking
- Trail de 15 pontos
- Cubos spawn procedural com direção de corte (8 direções)
- Corte baseado em velocidade >0.5m/s
- Score: 50 + vel*20 + combo*5
- Partículas + divisão do cubo

### Sistema

```js
PinaVR.system.vibrate([100,50,100]); // padrão
PinaVR.system.isAndroid(); // bool
PinaVR.system.isCardboard(); // true
await PinaVR.system.requestFullscreen();
```

### Eventos Globais

```js
PinaVR.on('hands', (hands) => {});
PinaVR.on('orientation', (quat, accel, gyro, euler) => {});
PinaVR.on('touch', ({object, hand, point}) => {});
```

### Native Bridge - Android Nativo

Se rodando dentro do APK Android, `window.PinaNative` está disponível:

```js
// Kotlin expõe via @JavascriptInterface
PinaNative.getSensors(); // JSON com quat, gyro, accel, mag 200Hz
PinaNative.vibrate("[100,50,100]");
PinaNative.setMRMode("mixed");
PinaNative.getDeviceInfo();
PinaNative.log("debug");

// Bridge JS recebe dados nativos
window.PinaNativeBridge = {
  onSensorData: (json) => {},
  onHandData: (json) => {}
};
```

## Criar um App Completo Exemplo

```js
// Meu App Espacial
const myPanel = PinaVR.ui.createPanel({ position: [0,1.6,-1.8], title: 'MEU APP' });

const btn = PinaVR.ui.createButton({
  label: 'ABRIR YOUTUBE 3D',
  position: [0,1.4,-1.75],
  color: 0xff0000,
  onClick: () => {
    PinaVR.browser.openYouTube();
    PinaVR.system.vibrate(50);
  }
});

// Usa hand tracking direto
PinaVR.hands.onHands((hands) => {
  if (hands[0]?.pinch) {
    // faz algo
  }
});

// Registra mesh custom como tocável
const myMesh = new THREE.Mesh(new THREE.BoxGeometry(0.2,0.2,0.2), new THREE.MeshBasicMaterial({color:0x00ff88}));
myMesh.position.set(0,1.2,-1);
scene.add(myMesh);
PinaVR.touch.registerObject(myMesh, {
  onClick: () => console.log('Meu cubo tocado em 6DOF!')
});
```

## Build Android

```bash
cd android
./gradlew assembleDebug
# APK em app/build/outputs/apk/debug/app-debug.apk
# Instale no celular, coloque no Cardboard
```

O APK carrega `web/` de `assets/pina/` via WebView imersivo com sensores 200Hz.
