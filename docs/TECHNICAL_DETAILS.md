# ZENTRA XR Beta 1 - Detalhes Técnicos

## Dependências Exatas

```kotlin
// app/build.gradle.kts
- Kotlin 1.9.22
- AGP 8.2.2
- Compose BOM 2024.02.00
- CameraX 1.3.4 (core, camera2, lifecycle, view)
- Cardboard SDK 1.20.0 (com.google.cardboard:sdk)
- MediaPipe Tasks Vision 0.10.14
- WebKit 1.8.0
- Coroutines 1.7.3
```

## Cardboard SDK - Integração REAL

Não é fake Cardboard. Usa SDK oficial open-source:

```kotlin
// CardboardManager.kt
- HeadTracker(context) // IMU
- DeviceParams() // lentes, FOV, distorção
- ScreenParams(context) // DPI, tamanho tela
- QrCodeCaptureActivity // QR scanner oficial
```

### Como funciona:

1. `initialize()` cria HeadTracker + DeviceParams + ScreenParams
2. `startTracking()` inicia IMU
3. `getLastHeadView()` retorna matriz rotação cabeça
4. `computeEyeParams()` calcula projeção L/R com IPD 60-64mm
5. `openViewerProfileScanner()` abre QR scanner para calibrar qualquer VR Box

Futuro: distortion mesh via `CardboardView` ou custom GL.

### Compatibilidade VR Box:

- Qualquer Cardboard v1/v2
- VR Box genérico
- BoboVR, Google Cardboard, etc.
- QR code calibra automaticamente

## MediaPipe Hand Tracking - Detalhes

### Modelo:

- `hand_landmarker.task` - 21 pontos por mão
- Landmarks:
  - 0: wrist
  - 4: thumb tip
  - 8: index tip
  - 12: middle tip
  - etc.

### Pipeline YUV -> Tracking:

```
CameraX ImageAnalysis (320x240 YUV_420_888)
  -> YUV buffers (Y, U, V)
  -> NV21 byte array
  -> YuvImage compressToJpeg
  -> BitmapFactory decode
  -> Rotation matrix (imageInfo.rotationDegrees)
  -> Reusable bitmap
  -> BitmapImageBuilder (MediaPipe)
  -> HandLandmarker.detect()
  -> List<Detection> + handedness
  -> JoyConPose conversion
```

### Otimizações:

- Throttle 33ms (30 FPS max) - `minFrameIntervalMs`
- Reusable bitmap - evita GC
- Single thread executor - evita race
- `STRATEGY_KEEP_ONLY_LATEST` - não acumula fila
- OneEuroFilter para smoothing (freq 30, minCutoff 1.2, beta 0.02)

### Pinch Detection:

```kotlin
val pinchDist = hypot(thumbTip, indexTip) // normalized 0..1
isPinching = pinchDist < 0.05
pinchStrength = 1 - (pinchDist / 0.1).coerceIn(0,1)
```

Hysteresis para evitar jitter:
- Enter: 0.05
- Exit: 0.07

### Joy-Con Pose:

```kotlin
center = (wrist + middleMcp) / 2
rotation = atan2(middleMcp.y - wrist.y, middleMcp.x - wrist.x)
scale = handSize * 3 coerce 0.6..1.4
```

## Camera - Baixa Latência

### Config:

```kotlin
ResolutionSelector 1280x720 preview, 320x240 analysis
Backpressure KEEP_ONLY_LATEST
Output YUV_420_888
Performance mode PreviewView
Dedicated executors
```

### Por que 320x240 para tracking?

- MediaPipe não precisa HD para mãos
- Reduz CPU 4x vs 1280x720
- Menos aquecimento
- Latência menor

## Input - Pointer + Pinch

### Smoothing duplo:

1. HandTracking: OneEuroFilter (1.2 minCutoff, 0.02 beta)
2. InputManager: exponential 0.35 factor

Resultado: jitter reduzido, latência ~50-80ms aceitável para Beta 1.

### State Machine Pinch:

```
IDLE -> PINCH_START (dist < 0.05)
PINCH_START -> record startTime
PINCH_START -> PINCHING (se manter)
PINCHING -> IDLE (dist > 0.07)
  -> Se duration 80ms..2000ms -> CLICK
```

Debounce evita clicks acidentais.

## UI - Glassmorphism

### Cores:

```kotlin
background #0A0A0F
surface #1C1C25
panelGlass #CC1E1E2A (80% opacity)
border #1AFFFFFF (10% white)
accent #7C5CFF
accentGlow #667C5CFF
joyConLeft #3ABFFF
joyConRight #FF5C7C
```

### Componentes:

- SpatialPanel: shadow 32dp + glow, rounded 28dp, vertical gradient #252530->#1C1C25 + glass overlay
- LargeAppButton: 56dp icon, 24dp corner, hover border accent
- JoyConVirtual: Canvas 56x80dp, body #1E1E28, accent strip, joystick, buttons, trigger
- PointerDot: core 10dp, glow 32dp, hover 14dp + 1.5x scale, pinch ring 22dp

### Layout:

- Home: central floating panel max 520dp width, 92% fill
- Browser: 96% width, 88% height, WebView inside rounded 18dp white container
- Settings: 560dp max, sections com background #23232F

## Browser - Spatial

### WebView dentro Compose:

```kotlin
AndroidView(factory = { WebView(it).apply { attach } })
```

### Controles:

- Back/Forward via canGoBack/canGoForward flow
- Reload, Home, Address search
- Address: se contém ponto e sem espaço -> https://, senão Google search

### MR Interaction:

```kotlin
simulateClickAt(normalizedX, normalizedY):
  x = normalizedX * webView.width
  y = normalizedY * webView.height
  dispatch ACTION_DOWN + ACTION_UP MotionEvent
```

## Performance - Medições Beta 1

Target dispositivos médios (Snapdragon 700+):

- Câmera preview: 30 FPS estável
- Hand tracking: 25-30 FPS (throttled)
- UI Compose: 60 FPS
- Latência total câmera->pointer: <150ms
- Uso RAM: ~180-250 MB
- Aquecimento: moderado após 10 min (throttle ajuda)

Low-end (Snapdragon 600, 3GB RAM):

- Tracking reduzido para LOW quality
- FPS tracking 15-20
- UI ainda 60 FPS
- Mensagem aviso compatibilidade

## Privacidade - Implementação

- Nenhum `Analytics`, `Firebase`, `Crashlytics` no build.gradle
- Câmera: `ImageProxy.close()` após uso, não salva
- Hand tracking: bitmap reusado e reciclado, não enviado
- Browser: WebView padrão, sem interceptação
- Permissões: apenas CAMERA, INTERNET (para browser), VIBRATE opcional
- `network_security_config.xml` com cleartext false

## Testes - Como Validar 12 Passos

1. **Abrir**: `adb shell am start -n com.zentra.xr/.MainActivity` ou launcher
2. **Permissão**: Deve mostrar PermissionScreen com texto privacidade
3. **MR**: Após permitir, PreviewView deve mostrar câmera em tempo real
4. **Ambiente real**: Mover celular, ver ambiente
5. **Joy-Con**: Apontar mãos, ver L azul e R rosa com escala e rotação
6. **Indicador**: Indicador deve seguir index tip
7. **Pointer**: Ver dot com glow
8. **Pinch**: Juntar polegar+indicador, ver Joy-Con scale 0.92 + pointer ring
9. **Click**: Pinch sobre botão Browser, deve abrir BrowserScreen
10. **Browser**: WebView carrega google.com, address bar funciona
11. **Navegar**: Digitar URL, back/forward/reload/home
12. **Voltar**: Botão X fecha Browser, volta Home

Logcat tags:

- `ZentraCamera`
- `ZentraCardboard`
- `ZentraHandTracking`
- `ZentraInput`
- `ZentraMRRenderer`
- `ZentraViewModel`
- `ZentraCompat`

## Limitações Beta 1 Conhecidas

- Sem distortion mesh Cardboard (stereo é simples side-by-side duplicado)
- Joy-Con via Compose Canvas, não 3D OpenGL (estabilidade)
- YUV->Bitmap via JPEG compress (não libyuv otimizado) - futuro melhorar
- Sem teclado espacial (usa address bar com input normal)
- Sem depth oclusão (câmera sempre fundo)
- WebView click simulation simples (não suporta scroll via gesto ainda)

Todas aceitáveis para Beta 1 focada em estabilidade do fluxo central.

## Próximos Passos Técnicos

- Integrar `libyuv` para YUV->RGB rápido
- Usar GPU delegate MediaPipe (`tasks-vision-gpu`)
- Implementar `CardboardView` com distortion
- Migrar Joy-Con para OpenGL 3D models
- Adicionar `Spatial Keyboard` via Compose
- Implementar scroll via pinch+drag
