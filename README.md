# PINA VR — Cardboard Mixed Reality OS

**Mixed Reality como padrão, VR como modo imersivo. Tudo 100% 3D spatial. Zero 2D.**

PINA VR é uma plataforma completa de Cardboard VR para Android. Não é um app 2D. É um OS espacial.

## Features Core

- **Cardboard Stereo Nativo**: renderização estéreo com distorção barrel, IPD ajustável, 60/90/120 FPS target
- **Giroscópio + Acelerômetro - Melhor Possível**:
  - Sensor Fusion com Complementary Filter + Madgwick AHRS + Kalman para drift correction
  - 1000Hz de interpolação via predição
  - Calibração automática de bias, low-pass adaptativo
  - Suporte a Android RotationVector nativo via Native Bridge
- **Mixed Reality (default)**: passthrough da câmera traseira mapeado em esfera invertida com depth estimation
- **Modo VR**: void espacial com skybox
- **Hand Tracking Real**:
  - MediaPipe Hands (Tasks Vision) rodando via WASM
  - **One Euro Filter** por landmark (minCutoff 1.2, beta 0.007, dCutoff 1.0)
  - **Kalman Filter** 3D para predição de velocidade e redução de jitter
  - Depth 6DOF: estimativa de Z via tamanho da mão + focal length + triangulação
  - Touch Direto: detecção de pinch + colisão 6DOF com superfícies 3D (não é touch de celular)
- **Spatial UI 100% 3D**: nenhum elemento DOM 2D na experiência. Botões são meshes com SDF, raycast de dedo
- **Navegador Espacial 3D + Multitarefa**:
  - Janelas curvas 3D (cylindrical, 120° FOV cada)
  - CSS3DRenderer + WebGL compositing para conteúdo real da web
  - Até 5 janelas simultâneas em arco
  - Atalhos: Discord, YouTube, totalmente dentro do navegador espacial
- **Jogos**: Pina Saber (Beat Saber clone completo com sabres presos às mãos)
- **Dev API Android**: `window.PinaVR` - API completa para devs criarem apps espaciais

## Arquitetura

```
web/
  src/core/         -> SensorFusion, XRManager, HandTracking, DepthTouch, MR
  src/filters/      -> OneEuro, Kalman (otimizados)
  src/browser/      -> SpatialBrowser multitask
  src/games/        -> Pina Saber
  src/api/          -> PinaAPI + NativeBridge
android/
  app/src/main/java/com/pinavr/os/
    MainActivity.kt          -> WebView imersivo + permissões
    sensors/SensorFusion.kt  -> Fusão nativa gyro+accel+mag (RotationVector + Kalman)
    camera/HandTrackingService.kt
```

## Rodar

### Web (PWA - teste no celular com Cardboard)
```bash
cd web
python3 -m http.server 8000
# Abra https://[seu-ip]:8000 no Android Chrome, ative sensores
```

### Android
Abra `android/` no Android Studio, build APK. O app injeta sensores nativos de alta frequência no WebView.

## Dev API

```js
// Criar janela 3D
const win = PinaVR.browser.createWindow({ url: 'https://discord.com/app', position: [0,1.5,-2], curved: true });

// Criar botão 3D espacial
const btn = PinaVR.ui.createButton({ label: 'PLAY', position: [0,1,-1], onTouch: () => {} });

// Hand tracking raw
PinaVR.hands.onHands((hands) => { /* hands[0].keypoints 21 + 6DOF */ });

// Sensor fusion
PinaVR.sensors.onOrientation((quat, accel) => {});

// Modo MR/VR
PinaVR.mr.setMode('mixed'); // 'mixed' | 'vr'
```

Licença: MIT - Pina VR OS
