# ZENTRA XR — Beta 1

> **Mixed Reality OS para VR Box / Cardboard — Primeiro Protótipo Funcional**
>
> Câmera + Hand Tracking + Joy-Con Virtual + Pointer + Pinch + UI Espacial + Cardboard REAL

![ZENTRA XR](https://img.shields.io/badge/ZENTRA%20XR-Beta%201-7C5CFF?style=for-the-badge)
![Android](https://img.shields.io/badge/Android-Nativo-3DDC84?style=flat-square&logo=android)
![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-7F52FF?style=flat-square&logo=kotlin)
![Cardboard](https://img.shields.io/badge/Cardboard-REAL%20SDK-FF7139?style=flat-square&logo=google)
![MediaPipe](https://img.shields.io/badge/MediaPipe-Hand%20Tracking-0091EA?style=flat-square)

---

## 🎯 Conceito

ZENTRA XR Beta 1 **NÃO** é:
- ❌ VR tradicional
- ❌ Jogo
- ❌ Loja / Plugins
- ❌ Contas / Multiplayer
- ❌ Tracking corporal

ZENTRA XR Beta 1 **É**:
- ✅ **Mixed Reality** com câmera como fundo
- ✅ **Hand Tracking** via MediaPipe (local, sem servidor)
- ✅ **Joy-Con Virtual** representando mãos (não mãos humanas)
- ✅ **Pointer** no indicador + **Pinch** (polegar+indicador) = Click
- ✅ **UI Espacial** inspirada em XR OS (VisionOS-like, glassmorphism)
- ✅ **Cardboard REAL** SDK integração, compatível com qualquer VR Box
- ✅ **ZENTRA Browser** único app interno

**Fluxo central que deve ser estável:**

```
CÂMERA → MÃO → JOY-CON VIRTUAL → APONTADOR → PINCH → UI
```

---

## 📱 Experiência Beta 1

Ao instalar, o usuário consegue:

1. Abrir ZENTRA XR
2. Permitir câmera (mensagem de privacidade clara)
3. Entrar no modo MR - ver ambiente real pela câmera
4. Ver 2 controladores virtuais (Joy-Con) nas mãos
5. Apontar com indicador - ver pointer seguir
6. Fazer pinch - ver animação
7. Usar pinch para clicar na interface
8. Abrir ZENTRA Browser
9. Navegar em página (back, forward, reload, address)
10. Voltar para ZENTRA XR Home

---

## 🛠️ Tecnologia

- **Nativo Android**: Kotlin + Android SDK + Android Studio
- **Sem Unity/Unreal**
- **Cardboard**: `com.google.cardboard:sdk:1.20.0` - integração REAL, não fake
  - `HeadTracker`, `DeviceParams`, `ScreenParams`, `QrCodeCaptureActivity`
  - IPD, eye matrices, stereo viewports preparado
  - Configuração headset via QR oficial
- **Hand Tracking**: MediaPipe Tasks Vision 0.10.14 `HandLandmarker`
  - Detecção esquerda/direita, 21 landmarks, index tip, thumb tip, pinch
  - 100% local, sem envio para servidores
- **Câmera**: CameraX com otimização agressiva
  - Preview 1280x720 max, Analysis 320x240 para tracking
  - `KEEP_ONLY_LATEST`, executors dedicados, buffer reuse
- **MR Renderer**: OpenGL ES 2.0/3.0 + Compose overlay
  - `SurfaceTexture` + `GL_TEXTURE_EXTERNAL_OES` para câmera background
  - Joy-Con e pointers via Compose Canvas para estabilidade Beta 1
- **UI**: Jetpack Compose + Material3
  - Glassmorphism, cantos 28dp, fundo escuro, dock, top bar
- **Browser**: WebView dentro de painel espacial, interação via MotionEvent simulation

---

## 📁 Estrutura

```
ZentraXR
├── Camera          # ZentraCameraManager - baixa latência, 320x240 analysis
├── Cardboard       # CardboardManager - REAL SDK, HeadTracker, EyeParams, QR
├── HandTracking    # MediaPipeHandTracker + Models + OneEuroFilter smoothing
├── MRRenderer      # MRRenderer - OpenGL ES, camera texture, stereo preparado
├── Input           # InputManager - pointer smoothing, pinch state machine, click
├── UI              # Compose - SpatialPanel, JoyConVirtual, PointerDot, Home/Browser/Settings
├── Browser         # ZentraBrowserManager - WebView spatial, back/forward/reload
└── Settings        # ZentraSettings + DeviceCompatibility checker
```

Ver `docs/ARCHITECTURE.md` para detalhes.

---

## 🚀 Como Rodar

### Pré-requisitos

- Android Studio Hedgehog+
- Android SDK 34
- Celular Android 8.0+ (minSdk 26) com câmera e giroscópio
- VR Box / Cardboard (opcional, mas recomendado)

### 1. Clone

```bash
git clone https://github.com/AkunDiscoRemake/zentra-xr.git
cd zentra-xr
```

### 2. Modelo Hand Tracking (IMPORTANTE)

Baixe o modelo MediaPipe:

```bash
# Crie pasta se não existir
mkdir -p app/src/main/assets

# Baixe LITE (recomendado Beta 1 - performance)
wget https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker_lite.task -O app/src/main/assets/hand_landmarker.task

# Ou FULL para precisão
# wget https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task -O app/src/main/assets/hand_landmarker.task
```

> **Sem o modelo, o app mostrará erro claro em vez de crashar** (requisito de compatibilidade).

Ver `app/src/main/assets/README_HAND_TRACKING.md`.

### 3. Abra no Android Studio

- Open Project → selecione pasta `zentra-xr`
- Sync Gradle
- Conecte celular via USB com Debug habilitado
- Run `app`

### 4. Teste em VR Box

1. Permita câmera
2. Coloque celular no VR Box
3. Aponte mãos para câmera
4. Veja Joy-Cons virtuais (L azul, R rosa)
5. Aponte com indicador → pointer segue
6. Pinch (indicador+polegar) → click
7. Abra Browser → navegue → volte Home

---

## 🎨 UI - Referência Visual

Inspirado em XR OS / VisionOS:

- Grande painel central flutuante
- Cantos arredondados 28dp
- Fundo escuro #0A0A0F
- Glassmorphism: `Color(0xCC1E1E2A)` com border `1AFFFFFF`
- Shadow 32dp com glow `667C5CFF`
- Top bar com logo Z e título
- Botões grandes 56dp icon, 24dp corner
- Dock inferior estilo VisionOS
- Pointer com glow e hover scale 1.5x
- Joy-Con Canvas: body escuro, joystick colorido, trigger indica pinch

---

## 🔒 Privacidade

- **Sem analytics invasivos**
- **Sem coleta de imagens**
- **Sem envio hand tracking para servidores**
- Câmera usada somente para MR local
- Tudo processado on-device
- Sem contas, sem login

---

## ⚡ Performance - Prioridades Beta 1

Beta 1 prioriza:

1. **LATÊNCIA BAIXA** - CameraX PERFORMANCE, KEEP_ONLY_LATEST, async executors
2. **FPS ESTÁVEL** - Throttle 30 FPS tracking, reuse bitmap, single thread
3. **TRACKING ESTÁVEL** - OneEuroFilter, hysteresis pinch (0.05/0.07), confidence filter
4. **BAIXO CONSUMO** - Downscale 320x240, no duplicate processing, low-ram detection

Evita aquecimento: não processa cada frame, reduz resolução, throttling.

---

## 🧪 Compatibilidade

Verificações em `DeviceCompatibility`:

- Câmera disponível?
- Acelerômetro, Giroscópio, Magnetômetro?
- OpenGL ES 3.0?
- Android 8.0+?
- RAM >= 2.5GB?
- Low-ram device?

Se incompatível, mostra mensagem clara em vez de crash.

---

## 🗺️ Roadmap

### Beta 1 (atual) ✅

- [x] Câmera MR background
- [x] Cardboard REAL SDK integração
- [x] Hand tracking MediaPipe local
- [x] Joy-Con virtual (não mão humana)
- [x] Pointer + Pinch
- [x] UI espacial Home
- [x] Browser espacial
- [x] Settings + compatibilidade

### Beta 2 (futuro)

- [ ] Distortion mesh real Cardboard
- [ ] Teclado espacial
- [ ] Multi-window
- [ ] Gestos 2 mãos (grab, swipe)
- [ ] Depth estimation oclusão
- [ ] Files, Media apps

### Beta 3+

- [ ] Spatial anchoring
- [ ] Cloud anchor opcional
- [ ] Plugin system
- [ ] Loja

---

## 📄 Licença

Projeto em desenvolvimento. Beta 1 é protótipo interno.

---

## 🤝 Contribuição

Beta 1 é base pequena, estável, bonita. Mantenha arquitetura simples e modular.

**Não adicionar nesta beta:**
- Jogos, loja, plugins, contas, multiplayer, tracking corporal, VR tradicional

Foco: fazer `CÂMERA → MÃO → JOY-CON → POINTER → PINCH → UI` funcionar extremamente estável.

---

**ZENTRA XR — Mixed Reality OS para VR Box**

*Versão: Beta 1 — 1.0.0-beta1*
