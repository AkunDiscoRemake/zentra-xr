# GitHub Actions - ZENTRA XR Build

## Workflows

### build.yml - Build Beta 1

Compila o ZENTRA XR Beta 1 automaticamente em cada push para `main` ou `arena/*`.

**O que faz:**

1. Checkout código
2. Setup JDK 17 + Android SDK
3. Setup Gradle 8.4
4. Gera `gradle-wrapper.jar` se faltando (necessário porque repo inicial não tem jar)
5. Baixa modelo MediaPipe `hand_landmarker_lite.task` para `app/src/main/assets/`
6. `./gradlew assembleDebug` - compila APK debug
7. `./gradlew assembleRelease` - compila APK release unsigned
8. Upload artifacts (APKs + lint report)

**Artifacts:**

- `zentra-xr-beta1-debug-apk` - APK debug pronto para instalar
- `zentra-xr-beta1-release-apk` - APK release unsigned
- `lint-report` - relatório lint

**Como usar:**

- Vá em Actions > ZENTRA XR - Build Beta 1 > último run > Artifacts > baixe APK
- Instale no celular Android 8.0+ com `adb install app-debug.apk`

**Requisitos atendidos:**

- ✅ Compilação nativa Kotlin, sem Unity
- ✅ Cardboard SDK REAL 1.20.0
- ✅ MediaPipe Tasks Vision 0.10.14
- ✅ CameraX, Compose, etc.
- ✅ Fallback se modelo não baixar (app mostra erro claro, não crasha)

**Badge:**

```markdown
![Build](https://github.com/AkunDiscoRemake/zentra-xr/actions/workflows/build.yml/badge.svg)
```
