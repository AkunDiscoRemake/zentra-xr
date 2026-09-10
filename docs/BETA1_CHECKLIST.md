# ZENTRA XR Beta 1 - Checklist de Entrega

## Objetivo da Beta 1

Fazer a experiência central funcionar de maneira extremamente estável:

```
CÂMERA → MÃO → JOY-CON VIRTUAL → APONTADOR → PINCH → UI
```

## Requisitos Funcionais

### 1. Abrir o ZENTRA XR
- [x] App Android nativo Kotlin
- [x] AndroidManifest com permissões câmera, IMU
- [x] Tema escuro XR OS, fullscreen immersive
- [x] Splash/loading screen

### 2. Permitir acesso à câmera
- [x] Request permission `CAMERA`
- [x] Overlay explicativo com mensagem de privacidade
- [x] Fallback se negado - mensagem clara, não crash

### 3. Entrar no modo MR
- [x] CameraX Preview como fundo (baixa latência)
- [x] MRRenderer com SurfaceTexture OES
- [x] Preview FILL_CENTER, PERFORMANCE mode
- [x] Manter câmera em tempo real

### 4. Ver ambiente real pela câmera
- [x] Câmera ocupando fundo da experiência
- [x] Sem ambiente 3D artificial por cima
- [x] Processamento local apenas

### 5. Ver dois controladores virtuais representando mãos
- [x] MediaPipe Hand Landmarker (tasks-vision 0.10.14)
- [x] Detecção mão esquerda/direita
- [x] Joy-Con virtual (não mão humana)
- [x] Canvas drawing: body, joystick, buttons, trigger
- [x] Cor L=azul, R=vermelho/rosa
- [x] Posição = palm center, rotação = wrist->middle
- [x] Escala baseada em hand size
- [x] Confidence alpha

### 6. Apontar com dedo indicador
- [x] Index tip (landmark 8) como pointer
- [x] Smoothing: OneEuroFilter + extra exponential (0.35)
- [x] Redução jitter
- [x] Baixa latência

### 7. Ver apontador seguir dedo
- [x] PointerDot composable
- [x] Glow + core dot
- [x] Hover: escala 1.5x, cor branca
- [x] Posição normalizada 0..1 -> screen coords

### 8. Fazer pinch
- [x] Thumb tip (4) + Index tip (8) distância
- [x] Threshold 0.05 enter, 0.07 exit (hysteresis)
- [x] PinchStrength 0..1
- [x] Animação Joy-Con scale 0.92 quando pinching
- [x] PointerDot ring quando pinching

### 9. Usar pinch para clicar na interface
- [x] InputManager state machine
- [x] Debounce 80ms min
- [x] ClickEvent com targetId via hitTest
- [x] Hover detection
- [x] Integração com Compose clickable

### 10. Abrir ZENTRA Browser
- [x] HomeScreen com botão Browser
- [x] BrowserScreen spatial panel
- [x] WebView dentro de janela virtual
- [x] Controles: back, forward, reload, home, address
- [x] Address bar com search fallback

### 11. Navegar em página
- [x] WebViewClient com loading state
- [x] canGoBack / canGoForward
- [x] simulateClickAt para MR interaction
- [x] MotionEvent dispatch

### 12. Voltar para Home
- [x] Botão fechar no Browser
- [x] Navegação entre ZentraScreen enum

## Requisitos Não-Funcionais

### Tecnologia
- [x] Kotlin/Java nativo - sem Unity/Unreal
- [x] Android SDK, Android Studio
- [x] Google Cardboard SDK REAL (com.google.cardboard:sdk:1.20.0)
- [x] MediaPipe para hand tracking
- [x] Câmera + sensores IMU
- [x] Arquitetura modular simples

### Cardboard
- [x] Integração REAL, não fake
- [x] DeviceParams, ScreenParams
- [x] HeadTracker IMU
- [x] Configuração headset via QR (QrCodeCaptureActivity)
- [x] Orientação câmera/tela
- [x] Renderização estereoscópica preparada (EyeParams, IPD, 2 viewports)
- [x] Compatibilidade diferentes VR Box

### Mixed Reality
- [x] Câmera como fundo principal
- [x] UI + pointers + Joy-Con sobre câmera
- [x] Baixa latência
- [x] Processamento local
- [x] Sem envio para servidores

### UI
- [x] Inspirada em referência XR OS
- [x] Grande painel central flutuante
- [x] Cantos arredondados 28dp
- [x] Aparência OS XR, fundo escuro
- [x] Transparência/glassmorphism
- [x] Barra superior, título central
- [x] Botões grandes, ícones simples
- [x] Barra inferior dock
- [x] Moderna headset XR

### Home
- [x] ZENTRA XR HOME
- [x] Apenas Browser + Settings

### Navegador
- [x] ZENTRA Browser único app interno
- [x] Campo endereço, voltar, avançar, recarregar, home
- [x] Conteúdo web dentro janela virtual
- [x] Interação indicador + pinch

### Performance
- [x] Processamento assíncrono
- [x] Threads adequadas (cameraExecutor, analysisExecutor, hand tracker executor)
- [x] Redução resolução frame para tracking (320x240)
- [x] Smoothing eficiente (OneEuroFilter)
- [x] Evitar processamento duplicado (KEEP_ONLY_LATEST)
- [x] Reutilização buffers (reusable bitmap)
- [x] Controle memória
- [x] Adaptação qualidade (TrackingQuality, low ram detection)
- [x] Evitar aquecimento (throttle 30fps)

### Compatibilidade
- [x] Verificação câmera
- [x] Sensores disponíveis
- [x] Compatibilidade Cardboard
- [x] Permissões
- [x] Capacidade aproximada dispositivo (RAM, lowRamDevice)
- [x] Mensagens claras em vez de crash

### Privacidade
- [x] Sem analytics invasivos
- [x] Sem coleta imagens câmera
- [x] Sem envio hand tracking para servidores
- [x] Câmera somente para MR

### Estrutura
- [x] ZentraXR
  - [x] Camera
  - [x] Cardboard
  - [x] HandTracking
  - [x] MRRenderer
  - [x] Input
  - [x] UI
  - [x] Browser
  - [x] Settings

## Como Testar

1. Clone repo
2. Baixe `hand_landmarker.task` lite e coloque em `app/src/main/assets/`
3. Abra no Android Studio
4. Sync Gradle
5. Conecte celular Android 8.0+ com câmera e gyro
6. Run `app` no device
7. Permita câmera
8. Coloque em VR Box
9. Veja ambiente real, mãos como Joy-Con, pointer, pinch para clicar
10. Abra Browser, navegue, volte

## Próximos Passos (pós Beta 1)

- [ ] Distortion mesh real Cardboard
- [ ] Depth estimation para oclusão
- [ ] Teclado espacial
- [ ] Multi-window
- [ ] Gestos 2 mãos
- [ ] Files, Media apps
- [ ] Cloud anchor (opcional)
