# NEON FORGE

**CONTROLE SUAS MÃOS. CONTROLE A ENERGIA.**

Jogo Android futurista de ação/interação em primeira pessoa, jogado **sem botões**:
as mãos do jogador — rastreadas pela câmera — são o controle. Explore uma
instalação abandonada cheia de hologramas, máquinas, portas automáticas e robôs,
carregue energia, crie escudos e desative inimigos usando gestos.

---

## ⚡ Destaques

- **Hand tracking real via câmera** — segmentação por cor de pele + contornos +
  casco convexo + detecção de pontas dos dedos (sem download de modelos, 100% offline).
- **Gestos estáveis** com histerese + suavização **One Euro Filter**:
  `APONTAR`, `PINCH`, `AGARRAR`, `ESCUDO (palma aberta)`, `ENERGIA (mão fechada)`,
  `DISPARAR` e `DUAS MÃOS`.
- **Renderização em primeira pessoa** (ray-caster neon) com fog de distância,
  iluminação lateral, bordas emissivas, portas deslizantes e efeito "bloom" falso.
- **Física real** nos objetos: núcleos/baterias com massa, colisão com paredes,
  gravidade, quique e arremesso (agarrar → puxar → soltar → lançar).
- **Combate sem violência gráfica** — robôs são desativados por efeitos de energia.
- **Áudio 100% procedural** — SFX e trilha ambiente gerados em tempo real (sem assets).
- **UI futurista em português** — menu, tutorial em cards animados, configurações,
  pausa, HUD minimalista e área de treinamento.
- **Presets gráficos** LOW / MEDIUM / HIGH / ULTRA + **detecção automática** que mede
  o FPS e rebaixa a qualidade se necessário.
- **Tratamento de erros completo** — câmera negada/indisponível, mão perdida,
  pouca luz, baixa performance, com mensagens claras em português.

---

## 🎮 Como jogar

| Gesto | Ação |
|---|---|
| 🫵 **APONTAR** (indicador estendido) | Mira / seleciona objetos |
| 🤏 **PINCH** (polegar + indicador) | Agarra objetos, abre portas, anda |
| ✊ **AGARRAR / ENERGIA** (mão fechada) | Segura objetos / **carrega energia** (segure) |
| 💥 **SOLTAR A ENERGIA** (apontar após carregar) | **Dispara** a energia |
| 🖐️ **ESCUDO** (palma aberta) | Cria escudo que bloqueia projéteis |
| 🙌 **DUAS MÃOS** | Pulso de energia (empurra/desativa robôs, abre portas) |

**Objetivo:** desative os robôs de cada onda e entregue **5 núcleos de energia no
reator** (anel verde no chão). Arremesse núcleos nos robôs para causar dano.

**Modo toque (fallback):** se a câmera não estiver disponível, o jogo continua
jogável: arraste para mirar, toque para interagir e use os botões
**ESCUDO** / **ENERGIA** no canto da tela.

---

## 🗂️ Arquitetura (modular)

```
com.neonforge
├── MainActivity / GameView / Game      # host, loop de render, máquina de estados
├── core/    Settings, Time             # configurações persistidas + presets
├── hand/    HandTracker, GestureSystem,# CV + reconhecimento de gestos
│            OneEuroFilter, HandState
├── camera/  CameraSource               # captura NV21 (câmera frontal)
├── render/  Renderer, Raycaster,       # pipeline 2D: raycaster, sprites,
│            Sprites, NeonFont,         # partículas, fonte neon
│            ParticleSystem
├── world/   Map, Player, Enemy,        # cenário, entidades, física,
│            Pickup, Projectile,        # interação
│            Physics, Interaction
├── combat/  CombatSystem               # energia, escudo, dano, ondas
├── audio/   AudioEngine, Synth         # SFX + música procedural
├── ui/      Ui, UiKit                  # menu, tutorial, settings, pausa
└── (tutorial)                          # treinamento embutido no Game
```

---

## 🔧 Compilando

O build **não usa Gradle**. Pipeline: `aapt2 → ecj → d8 → (zip+align) → apksigner`.

```bash
./build.sh
```

Resultado: **`neonforge/build/NEON-FORGE.apk`** (assinado v1+v2, `minSdk 26`,
`targetSdk 34`), pronto para instalar:

```bash
adb install -r neonforge/build/NEON-FORGE.apk
```

Veja `tools/README.md` para a toolchain necessária e `build.py` para o pipeline.

> Também é possível abrir o projeto no Android Studio (layout `neonforge/src`
> padrão). O caminho canônico e testado é o `build.sh`.

---

## 📱 Requisitos

- Android **8.0+** (API 26) — câmera frontal opcional (jogo funciona sem ela,
  em modo toque).
- Permissão de **câmera** solicitada no primeiro boot, antes de entrar no jogo.

---

## 🛡️ Segurança contra erros

Câmera indisponível, permissão negada, tracking não inicializado, mão perdida,
apenas uma mão, nenhuma mão, pouca luz e baixa performance são detectados e
tratados com mensagens claras — o jogo nunca trava e sempre oferece o modo toque
como alternativa.

---

## 📄 Licença

Código original: **MIT** (ver `LICENSE`). Binários da toolchain mantêm as licenças
de seus respectivos fornecedores (Apache 2.0 / EPL).
