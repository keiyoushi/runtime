# Contributing

## Set up

You need:

- **Android Studio Rabbit 2 (2026.2.2) or newer.** That is the first release that supports
  AGP 9.5, which this project uses (AGP 9.5.1, Gradle 9.9.0, Kotlin 2.4.10).
- **JDK 17 or newer** for Gradle. AGP 9.5 needs 17; CI and releases build with 21. Studio's
  bundled JetBrains Runtime works: Settings → Build, Execution, Deployment → Build Tools → Gradle
  → Gradle JDK.
- **Android SDK Platform 37** (compileSdk and targetSdk). Studio offers to install it on the
  first sync.

Open the repository root with File → Open and let Gradle sync. Studio writes `local.properties`
with your SDK path; it and `.idea/` are not tracked. No keys or other setup are needed for debug
builds: they are signed with your machine's debug key.

The repository ships two run configurations (in `.run/`):

- **Runtime (debug)** builds, installs and opens the host on the selected device or emulator.
  It uses the build variant chosen in Build → Select Build Variant, normally `debug`.
- **Unit tests** runs `testDebugUnitTest` for every project, contract tests included.

`.editorconfig` holds the same ktlint settings `spotlessCheck` uses, so Studio's Reformat Code
matches it. Run `./gradlew spotlessApply` before you commit.

## Layout

```
contract/        the library extensions bundle to reach the runtime (keiyoushi.lib.runtime),
                 published on JitPack as com.github.keiyoushi.runtime:contract:<tag>
api/<name>/      a module's contract interfaces (published as api-<name>)
modules/<name>/  a module's implementation
host/            the APK: extension metadata, the extension's factory, the launcher screen
build-logic/     Gradle conventions: library, module, host, publishing, signing
```

## Test in Mihon

A debug build has the same package as the released runtime (`keiyoushi.runtime`) but is signed
with your debug key, so Android won't install it over a release install. Uninstall the runtime
you got from the store or a release first, then run **Runtime (debug)**, or install the APK from
`host/build/outputs/apk/debug/`. The key isn't the store's, so Mihon lists the runtime as
untrusted: trust it in Browse → Extensions. To go back to releases, uninstall the debug build
and install from the store again.

## Add a module

1. `api/<name>/build.gradle.kts` applies `id("runtime.published")`, depends on
   `api(project(":contract"))` and sets `android { namespace = "keiyoushi.api.<name>" }`. Its
   interfaces extend `RuntimeContract`. They hold abstract functions only, each annotated
   `@Throws(Exception::class)`, and they use only types the app loads (Android, Java, Kotlin,
   coroutines, OkHttp, Jsoup).
2. `modules/<name>/build.gradle.kts`:

   ```kotlin
   plugins {
       id("runtime.module")
   }

   runtimeModule {
       name = "<name>"
       api = 1    // the API level this build serves
       minApi = 1 // the lowest it still serves
   }

   dependencies {
       implementation(project(":api:<name>"))
   }
   ```

3. The entry point is `object Entry` in package `keiyoushi.runtime.<name>`
   (`modules/<name>/src/keiyoushi/runtime/<name>/Entry.kt`), implementing the api's interfaces.
4. Add a contract test that registers `Entry` with `RuntimeHost.register` and binds the api
   through `RuntimeModule("<name>", api).bind<...>()`, as `modules/webviewpatch/test/` does. A green
   bind means every contract function resolves to the module's implementation.
5. If the module bundles a library, add its license as `host/assets/licenses/<library>.txt`. The
   launcher screen lists it next to the runtime's own license.

The host picks up every `modules/*` project by itself and generates its registry from them, so
nothing in `host/` changes. Raise `versionCode` in `host/build.gradle.kts`. Every directory
under `api/` and `modules/` with a `build.gradle.kts` is a project, so in Studio sync Gradle
(File → Sync Project with Gradle Files) after you add one.

## Build and test

In Studio, run **Unit tests**, or a single test class or function from the gutter. From the
command line:

```
./gradlew :host:assembleDebug       # host/build/outputs/apk/debug/
./gradlew testDebugUnitTest         # every project's unit tests
./gradlew spotlessCheck lint        # what CI checks besides the build
```

Release builds need the signing key and fail without it. Release signing reads `KEYSTORE_PATH`
or `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment.
Build with `--no-configuration-cache` when these are set. To build release without the key (as
PR checks do), sign it with the debug key:

```
./gradlew -Pruntime.debugSignRelease=true assembleRelease
```

## WebView snapshot check

`webview-snapshot.yml` (daily, on demand, and on `modules/webviewpatch/**` PRs) runs the webviewpatch
locator against the latest published Chromium **snapshot** WebView, via
`.github/scripts/webview-snapshot-check.sh` and `SnapshotCheckTest`. It fetches the `Android_Arm64`
snapshot (arm64-v8a + armeabi-v7a, the production 64+32 ABI layout) and fails when the locator can no
longer find the X-Requested-With adder — an early warning that a new WebView may stop patching. The
standalone 32-bit `Android` snapshot is checked too, but only informationally: its non-production
codegen can diverge from the shipped WebView, so a miss there is logged, not gated. **Limit:** the
snapshot is a non-official trunk build (no PGO/orderfile), so a pass is a smoke signal, not proof the
Google-signed Play WebView of that version patches; a fail is worth investigating but can be a
snapshot-only codegen difference. Run it locally against already-downloaded libs with
`WORKDIR=... .github/scripts/webview-snapshot-check.sh --local-dir <chromium-snapshots-tree>`.

## Release

1. Merge a PR that raises `versionCode`. CI checks that it exceeds the latest release's.
2. Push the tag `v<versionCode>` on that commit in `main`. `release.yml` first checks that the
   tag matches `versionCode`, that its commit is on `main` and that `versionCode` exceeds the
   latest release's, before it reads any secret. It then runs the tests, builds and signs the
   APK, drafts the GitHub Release with the APK and its SHA-256, rewrites the `repo` branch (the
   update store: `index.pb` and `index.json`, whose apkUrl is the Release asset) and publishes
   the Release last.
3. If the run fails, re-run it. A release or draft for the same tag counts as that run's own,
   so the re-run finishes it.

JitPack builds the `contract` and `api-*` artifacts for a tag when someone first requests them
(`jitpack.yml`; PR checks run the same `publishToMavenLocal`). Consumers can also request a
commit hash instead of a tag (`com.github.keiyoushi.runtime:contract:<commit>`), so a fix to
the published libraries alone doesn't need a runtime release.

The signing secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) belong in
the `release` environment (Settings → Environments), not in the repository's secrets. A repo
admin creates that environment, limits its deployments to `v*` tags, and moves the secrets
there; a tag ruleset can also restrict who creates `v*` tags.

## Signing

The release key is permanent. Android installs an update only when it is signed with the same
key, so changing the key means every user uninstalls and reinstalls the runtime. APKs are signed
with v2 and v3 signatures, but nothing rotates the key.

The keiyoushi org key must be in place before the first release: its four secrets in the
`release` environment and its SHA-256 certificate digest in `.github/signing-key-fingerprint`.
The store refuses an APK signed with any other key, and the release refuses the scaffold's
temporary key (`CN=Keiyoushi Runtime TEMP`) outright.
