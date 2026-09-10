# ZENTRA XR Beta 1 - Build Instructions

## Pré-requisitos

- Android Studio Hedgehog (2023.1.1) ou superior
- JDK 17
- Android SDK 34
- Dispositivo Android 8.0+ (API 26+) com câmera e giroscópio
- VR Box / Cardboard (opcional)

## Passo 1: Modelo Hand Tracking

O app precisa do modelo MediaPipe `hand_landmarker.task`.

### Opção A: Download automático (recomendado)

```bash
mkdir -p app/src/main/assets
curl -L https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker_lite.task -o app/src/main/assets/hand_landmarker.task
```

### Opção B: Download manual

1. Acesse https://developers.google.com/mediapipe/solutions/vision/hand_landmarker
2. Baixe `hand_landmarker_lite.task`
3. Renomeie para `hand_landmarker.task`
4. Coloque em `app/src/main/assets/`

> Se não colocar o modelo, o app não crasha - mostra mensagem clara de erro (requisito de compatibilidade).

## Passo 2: Abrir no Android Studio

```bash
# Clone
git clone https://github.com/AkunDiscoRemake/zentra-xr.git
cd zentra-xr

# Abra no Android Studio:
# File > Open > selecione pasta zentra-xr
```

Android Studio vai:
- Detectar `settings.gradle.kts`
- Baixar Gradle 8.4 automaticamente
- Sync dependências (CameraX, Cardboard SDK, MediaPipe, Compose)

Se Gradle wrapper jar faltando:

```bash
# No terminal do Android Studio ou sistema com Gradle instalado:
gradle wrapper --gradle-version 8.4
```

Ou: Android Studio > File > Sync Project with Gradle Files

## Passo 3: Configurar Device

- Habilite USB Debugging no celular
- Conecte via USB
- Aceite autorização RSA

Verifique compatibilidade:

- Câmera traseira
- Giroscópio (para Cardboard IMU)
- Android 8.0+

## Passo 4: Run

- Selecione device no dropdown
- Clique Run (Shift+F10)
- Ou Build > Build APK

Primeira build demora (baixa dependências):

- `androidx.camera:* 1.3.4`
- `com.google.cardboard:sdk:1.20.0`
- `com.google.mediapipe:tasks-vision:0.10.14`
- Compose BOM 2024.02.00

## Passo 5: Teste MR

1. App abre, pede permissão câmera
2. Permita
3. Veja preview câmera como fundo
4. Aponte mãos para câmera
5. Joy-Cons virtuais aparecem (L azul, R rosa)
6. Indicador segue dedo indicador
7. Pinch (polegar+indicador) = click
8. Clique em Browser
9. Navegue, volte Home
10. Coloque no VR Box para testar Cardboard

## Troubleshooting

### Câmera não inicia

- Verifique permissão
- Verifique `logcat` tag `ZentraCamera`
- Teste em outro device

### Hand tracking não funciona

- Verifique se `hand_landmarker.task` está em assets
- Logcat tag `ZentraHandTracking`
- Device low-end pode ter FPS baixo - normal, Beta 1 reduz para 30 FPS

### Cardboard não rastreia

- Verifique sensores: `adb shell dumpsys sensorservice`
- Logcat `ZentraCardboard`
- Modo mono funciona sem gyro, mas stereo precisa

### Build falha - Cardboard SDK

```gradle
// Se maven não encontrar cardboard, adicione em settings.gradle.kts:
repositories {
    google()
    mavenCentral()
}
```

Cardboard SDK 1.20.0 está no Maven Central.

### Build falha - MediaPipe

MediaPipe Tasks Vision 0.10.14 precisa NDK abiFilters arm64-v8a, armeabi-v7a (já configurado em build.gradle.kts).

Se erro NDK, instale NDK via SDK Manager.

### Aquecimento / bateria

Beta 1 já otimizado:

- Analysis 320x240 (não full HD)
- Throttle 30 FPS
- Reusable bitmap
- KEEP_ONLY_LATEST

Se ainda esquenta, reduza tracking quality para LOW em Settings (futuro).

## Gerar APK Release

```bash
./gradlew assembleRelease

# APK em:
# app/build/outputs/apk/release/app-release.apk
```

Assine com keystore para Play Store (não necessário Beta 1 interna).

## Estrutura de Build

- `compileSdk 34`
- `minSdk 26`
- `targetSdk 34`
- `Kotlin 1.9.22`
- `AGP 8.2.2`
- `Compose Compiler 1.5.8`
- `Java 17`

## Próximos Passos

Ver `docs/BETA1_CHECKLIST.md` e `docs/ARCHITECTURE.md`
