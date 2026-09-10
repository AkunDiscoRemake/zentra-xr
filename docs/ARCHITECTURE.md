# ZENTRA XR - Arquitetura Beta 1

## Visão Geral

ZENTRA XR Beta 1 é um aplicativo Android nativo de Mixed Reality focado em simplicidade, estabilidade e performance. A arquitetura é modular e preparada para evoluir.

```
ZentraXR
├── Camera          # Câmera MR - fundo em tempo real, baixa latência
├── Cardboard       # Integração REAL Google Cardboard SDK
├── HandTracking    # MediaPipe Hand Landmarker - local, sem servidor
├── MRRenderer      # Compositor OpenGL ES - câmera + virtual
├── Input           # Pointer + Pinch gesture -> Click
├── UI              # Spatial UI - glassmorphism, XR OS inspired
├── Browser         # ZENTRA Browser - único app interno Beta 1
└── Settings        # Compatibilidade, Cardboard config, privacidade
```

## Módulos Detalhados

### Camera (ZentraCameraManager)
- **Tecnologia**: CameraX (Camera2 underneath)
- **Otimizações**:
  - Preview: 1280x720 max para performance
  - Analysis: 320x240 para hand tracking (redução agressiva)
  - `STRATEGY_KEEP_ONLY_LATEST` - evita fila
  - Executors dedicados - single thread para evitar contenção
  - Buffer reuse
- **Latência**: mínima, processamento assíncrono

### Cardboard (CardboardManager)
- **REAL SDK**: `com.google.cardboard:sdk:1.20.0` - não fake
- **Features**:
  - `HeadTracker` - IMU (accel + gyro + magnetometer)
  - `DeviceParams` / `ScreenParams` - perfil do headset
  - `QrCodeCaptureActivity` - configuração QR oficial
  - Eye matrices para render estéreo (L/R viewports)
  - IPD handling
- **Modos**:
  - Mono (default Beta 1) - preview único
  - Stereo - duplicado L/R com offset IPD (60-64mm)
- **Futuro**: distortion mesh, lens correction

### HandTracking (MediaPipeHandTracker)
- **Modelo**: MediaPipe Tasks Vision HandLandmarker 0.10.14
- **Local**: 100% on-device, sem servidor
- **Detecção**:
  - Mão esquerda/direita
  - 21 landmarks por mão
  - Index tip (8), Thumb tip (4), Wrist (0)
  - Pinch: distância thumb-index < 0.05
- **Otimizações**:
  - Throttle 30 FPS max (minFrameInterval 33ms)
  - Reusable bitmap - evita alocações
  - YUV_420_888 -> JPEG -> Bitmap (caminho rápido, futuro usar libyuv)
  - OneEuroFilter + exponential smoothing para jitter
  - Confidence filtering
- **Representação**: NÃO mão humana virtual, mas Joy-Con virtual (controle VR)

### MRRenderer (MRRenderer)
- **OpenGL ES 2.0/3.0**
- **Camera background**: `SurfaceTexture` + `GL_TEXTURE_EXTERNAL_OES`
- **Shader**: OES external texture sampling
- **Pipeline**:
  1. Clear
  2. Update SurfaceTexture
  3. Draw camera quad
  4. (Futuro) Draw Joy-Con 3D models via GL
- **Beta 1**: Joy-Con e pointers via Compose overlay para estabilidade
- **Stereo path**: preparado para 2 viewports (half width each)

### Input (InputManager)
- **Pointer**: index tip com smoothing extra (0.35 factor)
- **Pinch State Machine**:
  - Enter threshold 0.05
  - Exit threshold 0.07 (hysteresis)
  - Debounce 80ms min, 2000ms max
  - Detecta click ao soltar pinch
- **Hit Testing**: callback para UI saber hover target
- **Flow**: TrackingFrame -> Pointers -> ClickEvents

### UI (Compose)
- **Design System**: VisionOS / XR OS inspired
  - Grande painel central flutuante
  - Cantos arredondados 28dp
  - Fundo escuro #0A0A0F
  - Glassmorphism: `Color(0xCC1E1E2A)` semi-transparente
  - Barra superior com branding ZENTRA
  - Botões grandes 56dp icon
  - Dock inferior
- **Componentes**:
  - `SpatialPanel` - shadow 32dp + glow accent
  - `LargeAppButton` - app launcher
  - `JoyConVirtual` - Canvas drawing de controle
  - `PointerDot` - glow + core dot + pinch ring
  - `TopBar`, `DockBar`
- **Telas**:
  - Home: apenas Browser + Settings
  - Browser: address bar + WebView spatial window
  - Settings: Cardboard, MR, Compatibilidade

### Browser (ZentraBrowserManager)
- **WebView** dentro de painel espacial
- **Controles**: back, forward, reload, home, address
- **Interação MR**:
  - Pointer position -> MotionEvent para WebView
  - Pinch = click
  - Simula ACTION_DOWN + ACTION_UP
- **Privacy**: sem coleta, apenas navegação

### Settings (ZentraSettings)
- **Local**: SharedPreferences
- **Opções Beta 1**:
  - Stereo toggle
  - Tracking quality (LOW/BALANCED/HIGH)
  - Hand smoothing
- **Compatibilidade**: DeviceCompatibility checker
  - Câmera, sensores, RAM, Android version
  - Mensagens claras em vez de crash

## Fluxo Principal

```
1. MainActivity onCreate
   -> Check permission CAMERA
   -> ViewModel init
      -> DeviceCompatibility.check()
      -> CardboardManager.initialize() [REAL SDK]
      -> MediaPipeHandTracker.initialize() [hand_landmarker.task]

2. Compose MRContainer
   -> PreviewView (camera background)
   -> ViewModel.bindCamera()
      -> CameraX bind Preview + ImageAnalysis (320x240)
      -> ImageAnalysis analyzer -> handTracker.processImageProxy()
         -> YUV -> Bitmap (reused)
         -> HandLandmarker.detect()
         -> JoyConPose (center, rotation, pinch)
         -> TrackingFrame flow

3. InputManager.updateFromTracking()
   -> Smoothing
   -> PointerState (position, isPinching, isHovering)
   -> Pinch state machine -> ClickEvent

4. UI rendering
   -> JoyConVirtual at pose.center + rotation
   -> PointerDot at indexTip
   -> HomeScreen spatial panel
   -> On pinch click -> navigate to Browser

5. BrowserScreen
   -> WebView inside spatial panel
   -> InputManager click -> browserManager.simulateClickAt()

6. Cardboard IMU
   -> HeadTracker.startTracking()
   -> getLastHeadView() -> head matrix
   -> Future stereo eye params
```

## Performance Priorities

1. **LATÊNCIA BAIXA**: CameraX PERFORMANCE mode, KEEP_ONLY_LATEST, async
2. **FPS ESTÁVEL**: throttle 30fps tracking, reuse bitmap, single thread executors
3. **TRACKING ESTÁVEL**: OneEuroFilter, hysteresis pinch, confidence filter
4. **BAIXO CONSUMO**: downscale 320x240, no duplicate processing, low ram device detection

Evita aquecimento: não processar cada frame, reduzir resolução, throttling.

## Privacidade

- Nenhum analytics invasivo
- Câmera nunca enviada para servidor
- Hand tracking 100% local
- WebView normal, sem interceptação
- Sem contas, sem login, sem coleta

## Evolução Futura (pós Beta 1)

- Cardboard: distortion mesh, lens correction, QR profile persistence
- MRRenderer: full OpenGL Joy-Con 3D models, hand mesh optional
- Input: 2-hand gestures, grab, swipe
- UI: multi-window, spatial anchoring, passthrough depth
- Browser: tabs, bookmarks, spatial keyboard
- Novos módulos: Files, Media, etc.

## Estrutura de Pastas

```
app/src/main/java/com/zentra/xr/
├── camera/
│   └── ZentraCameraManager.kt
├── cardboard/
│   └── CardboardManager.kt
├── handtracking/
│   ├── HandTrackingModels.kt
│   └── MediaPipeHandTracker.kt
├── mrrenderer/
│   └── MRRenderer.kt
├── input/
│   └── InputManager.kt
├── ui/
│   ├── theme/
│   │   └── ZentraTheme.kt
│   ├── components/
│   │   ├── SpatialComponents.kt
│   │   ├── JoyConView.kt
│   │   └── PermissionAndOverlay.kt
│   ├── screens/
│   │   ├── HomeScreen.kt
│   │   ├── BrowserScreen.kt
│   │   └── SettingsScreen.kt
│   └── MRContainer.kt
├── browser/
│   └── ZentraBrowserManager.kt
├── settings/
│   └── ZentraSettings.kt
├── util/
│   └── DeviceCompatibility.kt
├── MainActivity.kt
├── MainViewModel.kt
└── ZentraApplication.kt
```
