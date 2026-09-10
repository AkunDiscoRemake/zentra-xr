# Pina XR 🍍🥽

[![Build APK](https://github.com/AkunDiscoRemake/zentra-xr/actions/workflows/android.yml/badge.svg)](https://github.com/AkunDiscoRemake/zentra-xr/actions/workflows/android.yml)

**Pina XR** é um **launcher VR estilo Meta Quest** para Android — 100% VR
estéreo (Google Cardboard) com **Mixed Reality**: o mundo real aparece via
passthrough da câmera frontal (Camera2), suas mãos aparecem como um
**esqueleto 3D** (MediaPipe Hand Landmarker suavizado com **One Euro + Kalman**)
e você controla um **apontador 3D (linha + bolinha)** com clique pelo
**gesto pinch (juntar polegar e indicador)**.

> **Sem UI 2D. Nenhuma.** Tudo vive em painéis 3D dentro do mundo estéreo,
> renderizados com a API oficial do **Cardboard SDK** (distorção de lente,
> perfis de visor por QR Code e head tracking **3DOF**).

**Status: beta 0.2.0 ("Quest caseira")** — launcher com 5 apps. 🙂

## Os 5 apps do launcher

| App | O que faz |
|---|---|
| 🌐 **NAVEGADOR** | Navegador 3D: moldura com barra, FECHAR/INÍCIO, páginas HTML→texto (sem JS), imagens e **links pincháveis** |
| 🎬 **VÍDEOS** | **Vídeos do celular** via MediaStore: lista pinchável, player em painel gigante (aspect-fit) com ▶️/-10s/+10s/✖ e HUD com tempo (MediaPlayer → textura OES) |
| 🌍 **360 GRAUS** | Os mesmos vídeos, mas tocando **dentro de uma esfera 360°** equiretangular ao redor de você (vídeos 360 reais) |
| 🎯 **ALVOS 3D** | Jogo: 5 alvos 3D ao redor, pinche para acertar, **60 segundos**, placar no HUD |
| 🧠 **SIMON 3D** | Jogo de memória: 4 pads coloridos 3D, a sequência cresce a cada acerto |

Painel de **status** no launcher mostra **FPS das mãos e da câmera** (debug
ao vivo) e se a mão está sendo vista.

## Recursos técnicos

| Recurso | Como funciona |
|---|---|
| VR estéreo | Cardboard SDK (`CardboardHeadTracker`, `CardboardLensDistortion`, `CardboardDistortionRenderer`) + QR Code do visor |
| Mixed Reality | Passthrough da câmera **frontal** (Camera2 + ImageReader YUV→RGBA, tamanho negociado com a câmera) projetado numa esfera ao redor do usuário |
| 3DOF | `CardboardHeadTracker_getPose` (giroscópio + acelerômetro, com previsão de pose) |
| Hand tracking | MediaPipe **Hand Landmarker** (LIVE_STREAM, 2 mãos, 21 pontos) → filtro **One Euro** + **Kalman** (velocidade constante) por landmark/eixo → esqueleto 3D |
| Apontador | Raio saindo do meio do pinçar, com **linha** e **bolinha**; verde = pinçando, amarelo = sobre algo clicável |
| Clique | Borda de subida do **pinch** (histerese por mão) → raycast nos painéis/regiões/links/alvos/pads |
| Launcher | Textura 640×480 com 5 tiles circulares (ícones desenhados em canvas próprio), re-ancoragem quando você vira a cabeça |
| Vídeos | MediaStore (lista) → **MediaPlayer** → **SurfaceTexture OES** criada na GL thread → shader nativo (`u_TexMatrix`); 360° = equiretangular na esfera |
| Botão do visor | Toque na tela = mesmo efeito do pinch |

## Estrutura do projeto

```
pina-xr/
├── app/                      # o app Pina XR
│   ├── build.gradle          # baixa o modelo MediaPipe + extrai NDK do :sdk
│   ├── CMakeLists.txt        # lib nativa pina_jni
│   └── src/main/
│       ├── java/com/pina/xr/
│       │   ├── PinaActivity.java         # activity VR (sem UI 2D)
│       │   ├── vr/PinaRenderer.java      # loop estéreo (GL thread) + pontes
│       │   ├── camera/CameraReader.java  # Camera2 + YUV->RGBA + rotação
│       │   ├── camera/CameraFrameBus.java# double buffer câmera -> GL
│       │   ├── hands/HandTrackingManager.java # MediaPipe + One Euro + Kalman
│       │   ├── video/VideoPlayerController.java # MediaStore + MediaPlayer+OES
│       │   └── browser/BrowserContentManager.java # fetch+render+links
│       └── jni/
│           ├── pina_app.{h,cc}   # cena 3D: launcher, apps, jogos, passthrough,
│           │                     # mãos, apontador, raycast, Cardboard
│           ├── pina_jni.cc       # ponte JNI
│           ├── util.{h,cc}       # matemática/OBJ/texturas (do sample oficial)
│           └── font.h            # fonte bitmap 5x7 dos painéis
├── cardboard/                # SUBMÓDULO: googlevr/cardboard v1.35.0
├── proto/cardboard_device.proto  # necessário pelo módulo :sdk
└── settings.gradle           # inclui ':sdk' do submódulo
```

## Como compilar

### Requisitos
- **Android Studio** (com NDK + CMake pelo SDK Manager)
- minSdk **26** (Android 8.0) · targetSdk 35 · arm64-v8a
- Internet na primeira build (Gradle + modelo do MediaPipe)

### Passos
1. **Clone com os submódulos** (o Cardboard SDK vem de `googlevr/cardboard`):
   ```bash
   git clone --recurse-submodules <este-repo>
   cd pina-xr
   # se já clonou sem submódulos:
   git submodule update --init --recursive
   ```
2. Abra a pasta no **Android Studio** e aguarde o Gradle sync.
3. Conecte um celular e rode ▶️ (`app`).

Na primeira build, o task `downloadHandModel` baixa o modelo
`hand_landmarker.task` (~8 MB) para `app/src/main/assets/` e o task
`extractNdk` extrai `libGfxPluginCardboard.so` + `cardboard.h` do AAR do
módulo `:sdk` (mesmo fluxo do sample oficial `hellocardboard-android`).

> Sem terminal? O Android Studio resolve o wrapper Gradle automaticamente
> (Gradle 9.6.1 / AGP 9.2.0, mesmas versões do Cardboard v1.35.0).

### CI (GitHub Actions) 🤖

O workflow **`.github/workflows/android.yml`** compila o app em cada push/PR
(checkout com submódulos → JDK 21 → Gradle 9.6.1 → `assembleDebug
assembleRelease`) e publica os **APKs como artefato** do run (aba *Actions* →
run → *Artifacts* → `pina-xr-apks`). O release é assinado com a chave debug
para ser instalável direto no celular (beta).

## Como usar

1. Conceda as permissões de **câmera** (MR + mãos) e de **vídeos** (app
   VÍDEOS; sem ela o painel avisa "sem permissão").
2. Na primeira execução, **escaneie o QR Code** do seu visor Cardboard
   (impresso na caixa) — igual aos apps Cardboard oficiais.
3. Coloque o celular no visor.
4. Levante a **mão na frente do rosto**: o esqueleto aparece e o apontador
   (linha + bolinha) sai do gesto de pinçar.
5. Junte **polegar + indicador** (pinch) sobre um app do launcher para abrir.

### Launcher
- 2 fileiras de apps: **NAVEGADOR**, **VÍDEOS**, **360 GRAUS**, **ALVOS 3D**,
  **SIMON 3D** (+ painel de status com FPS).
- Se você vira a cabeça, os painéis **re-ancoram** na sua frente.

### VÍDEOS / 360
- Pinche um vídeo da lista para tocar. No player: **X** fecha, **-10s/+10s**,
  **▶️/⏸**. No modo 360, olhe ao redor — o vídeo envolve a cena.

### Jogos
- **ALVOS 3D**: pinche as bolinhas vermelhas antes do tempo acabar; HUD mostra
  pontos/tempo, com REINICIAR/SAIR.
- **SIMON 3D**: assista à sequência acendendo e repita pinçando os pads;
  errar encerra (REINICIAR/SAIR no HUD).

### Navegador 3D
- **FECHAR** (X vermelho) volta ao launcher.
- **INÍCIO** (casinha verde) recarrega a página inicial.
- Links das páginas ficam **destacados** e são pincháveis.

## Ajustes finos (tuning)

Tudo em `app/src/main/jni/pina_app.h`:

- `kCameraFovX/kCameraFovY` — FOV da câmera do seu aparelho (alinha
  passthrough e mãos).
- `kPassthroughMirror` — espelhar o passthrough se ficar invertido.
- `kHandDistance/kHandZScale` — profundidade em que as mãos aparecem.
- `kPanelDistance/kPanelHeight` — posição dos painéis.

No Java: `HandTrackingManager.PINCH_ON/PINCH_OFF` (sensibilidade do pinch),
`ONE_EURO_*` (suavização das mãos), tamanho preferido da câmera em
`CameraReader.PREFERRED_WIDTH/HEIGHT`.

## Limitações da beta (0.2)

- Navegador **sem JavaScript** (páginas estáticas), sem teclado 3D ainda.
- Passthrough por câmera frontal 2D projetada em esfera (sem profundidade).
- Sem 6DOF (Cardboard é 3DOF mesmo).
- Lista de vídeos mostra até 6 itens por vez (pinche ATUALIZAR para rever).
- DRM/streams protegidos (Netflix etc.) não tocam (MediaPlayer/MediaStore).

## Licenças / créditos

- **Cardboard SDK** — googlevr/cardboard (Apache 2.0), usado como submódulo.
- **MediaPipe Tasks Vision** — google-ai-edge/mediapipe (Apache 2.0).
- util matemático derivado do sample oficial `hellocardboard-android`
  (Apache 2.0).
