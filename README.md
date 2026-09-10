# Pina XR 🍍🥽

**Pina XR** é um app Android **100% VR estéreo (Google Cardboard) com Mixed
Reality**: o mundo real aparece via passthrough da câmera frontal (Camera2),
suas mãos aparecem como um **esqueleto 3D** (MediaPipe Hand Landmarker) e você
controla um **apontador 3D (linha + bolinha)** com clique pelo **gesto pinch
(juntar polegar e indicador)**. Também inclui um **navegador web 3D** com
links clicáveis dentro do mundo.

> **Sem UI 2D. Nenhuma.** Todos os menus vivem em painéis 3D dentro do mundo
> estéreo, renderizados com a API oficial do **Cardboard SDK** (distorção de
> lente, perfis de visor por QR Code e head tracking **3DOF**).

**Status: primeira beta (0.1.0-beta)** — simples de propósito. 🙂

## Recursos da beta

| Recurso | Como funciona |
|---|---|
| VR estéreo | Cardboard SDK (`CardboardHeadTracker`, `CardboardLensDistortion`, `CardboardDistortionRenderer`) + QR Code do visor |
| Mixed Reality | Passthrough da câmera **frontal** (Camera2 + ImageReader YUV→RGBA) projetado numa esfera ao redor do usuário |
| 3DOF | `CardboardHeadTracker_getPose` (giroscópio + acelerômetro, com previsão de pose) |
| Hand tracking | MediaPipe **Hand Landmarker** (LIVE_STREAM, 2 mãos, 21 pontos) renderizado como esqueleto (ossos + juntas) |
| Apontador | Raio saindo do meio do pinçar (polegar+indicador), com **linha** e **bolinha** na ponta; verde = pinçando, amarelo = sobre algo clicável |
| Clique | Borda de subida do **pinch** (com histerese) → raycast nos painéis/links |
| Painel inicial | 3 botões 3D: **NAVEGADOR**, **VISOR** (trocar visor/QR), **SAIR** |
| Navegador 3D | Moldura com barra de endereço, **FECHAR** e **INÍCIO**; páginas HTML→texto (sem JS), imagens e **links pincháveis** |
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
│       │   ├── vr/PinaRenderer.java      # loop estéreo (GL thread)
│       │   ├── camera/CameraReader.java  # Camera2 + YUV->RGBA + rotação
│       │   ├── camera/CameraFrameBus.java# double buffer câmera -> GL
│       │   ├── hands/HandTrackingManager.java # MediaPipe Hands + pinch
│       │   └── browser/BrowserContentManager.java # fetch+render+links
│       └── jni/
│           ├── pina_app.{h,cc}   # cena 3D: passthrough, mãos, apontador,
│           │                     # painéis, raycast, estereoscopia Cardboard
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

## Como usar

1. Conceda a permissão de **câmera** (obrigatória para MR e mãos).
2. Na primeira execução, **escaneie o QR Code** do seu visor Cardboard
   (impresso na caixa) — igual aos apps Cardboard oficiais. Você pode trocar
   depois em **VISOR**.
3. Coloque o celular no visor.
4. Levante a **mão na frente do rosto**: o esqueleto aparece e o apontador
   (linha + bolinha) sai do gesto de pinçar.
5. Junte **polegar + indicador** (pinch) sobre um botão ou link para clicar.

### Painel inicial
- **NAVEGADOR** → abre o navegador 3D (página inicial com atalhos).
- **VISOR** → escanear outro QR Code de visor.
- **SAIR** → fecha o app.

### Navegador 3D
- **FECHAR** (X vermelho) volta ao painel inicial.
- **INÍCIO** (casinha verde) recarrega a página inicial.
- Links das páginas ficam **ciano** e são pincháveis.

## Ajustes finos (tuning)

Tudo em `app/src/main/jni/pina_app.h`:

- `kCameraFovX/kCameraFovY` — FOV da câmera do seu aparelho (alinha
  passthrough e mãos).
- `kPassthroughMirror` — espelhar o passthrough se ficar invertido.
- `kHandDistance/kHandZScale` — profundidade em que as mãos aparecem.
- `kPanelDistance/kPanelHeight` — posição dos painéis.

No Java: `CameraReader.CAPTURE_WIDTH/HEIGHT` (resolução da câmera),
`HandTrackingManager.PINCH_ON/PINCH_OFF` (sensibilidade do pinch).

## Limitações da beta (0.1)

- Navegador **sem JavaScript** (páginas estáticas), sem teclado 3D ainda.
- Passthrough por câmera frontal 2D projetada em esfera (sem profundidade).
- Sem 6DOF (Cardboard é 3DOF mesmo).
- Perfil do visor salvo via fluxo oficial do Cardboard (QR Code).

## Licenças / créditos

- **Cardboard SDK** — googlevr/cardboard (Apache 2.0), usado como submódulo.
- **MediaPipe Tasks Vision** — google-ai-edge/mediapipe (Apache 2.0).
- util matemático derivado do sample oficial `hellocardboard-android`
  (Apache 2.0).
