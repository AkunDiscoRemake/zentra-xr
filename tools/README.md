# Toolchain do build (sem Gradle)

O projeto **NEON FORGE** é compilado com um pipeline manual que não depende de
Gradle, Android Studio, Maven nem AndroidX. Os binários abaixo são os únicos
pré-requisitos:

| Arquivo        | Função                                   | Origem (Android SDK)                  |
|----------------|------------------------------------------|----------------------------------------|
| `aapt2`        | compila/liga recursos + AndroidManifest  | `build-tools/<v>/aapt2` (linux-x64)    |
| `android.jar`  | stub da API Android (compile-time)       | `platforms/android-34/android.jar`     |
| `ecj.jar`      | compilador Java (Eclipse Compiler)       | `org.eclipse.jdt:ecj` (Maven Central)  |
| `d8.jar`       | conversor `.class` → `classes.dex`       | `build-tools/<v>/lib/d8.jar`           |
| `apksigner.jar`| assinatura v1+v2                         | `build-tools/<v>/lib/apksigner.jar`    |

Além disso é necessário um **JRE/JDK 8+** (`java`) e o `keytool` para gerar a
keystore de assinatura.

O `build.sh` procura a toolchain em `$NEONFORGE_TOOLS`, em `./tools`, ou em
`/home/user/toolchain/bin`. O build final:

```bash
./build.sh
# -> neonforge/build/NEON-FORGE.apk (assinado, v1+v2, minSdk 26 / targetSdk 34)
```

Verificação do APK gerado:

```bash
apksigner verify --verbose neonforge/build/NEON-FORGE.apk
aapt2 dump badging neonforge/build/NEON-FORGE.apk
```

> Todos os binários de terceiros mantêm suas respectivas licenças originais
> (Apache 2.0 / Eclipse Public License) e **não** são redistribuídos neste
> repositório. Para este workspace, a toolchain pronta já está em
> `/home/user/toolchain/bin` (aapt2, android.jar, ecj.jar, d8.jar,
> apksigner.jar, tools.jar, debug.keystore) e o `build.sh` a localiza
> automaticamente.
