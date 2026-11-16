# Keiyoushi Runtime

Shared code for [Keiyoushi](https://github.com/keiyoushi/extensions-source) extensions, installed
once as its own extension. Extensions that need a runtime module (for example a WebView patch)
call it through the small `contract` library they bundle. If the runtime is missing or too old,
they tell you to install or update it.

The runtime is an extension APK (package `keiyoushi.runtime`) with no sources. It is also an app:
its launcher screen shows the version, the modules and their API levels, and the licenses of the
runtime and of what it bundles.

## Install

The runtime works with [Mihon](https://mihon.app) only. Setup help:
[keiyoushi.github.io/runtime](https://keiyoushi.github.io/runtime).

1. Download the APK from the [latest release](https://github.com/keiyoushi/runtime/releases/latest)
   (always the newest:
   [keiyoushi-runtime.apk](https://github.com/keiyoushi/runtime/releases/latest/download/keiyoushi-runtime.apk),
   or from the docs page) and install it.
2. Open **Keiyoushi Runtime** from the launcher and tap **Add update store to Mihon** (each
   installed Mihon build gets its own button). Mihon adds the runtime's store and from then on
   updates the runtime like any other extension. The store's key matches the APK's, so Mihon
   trusts the runtime without asking.

Or add the store first, as the docs page shows, and install from Mihon: in Mihon's extension
store settings, add

```
https://github.com/keiyoushi/runtime/raw/repo/index.pb
```

then install **Keiyoushi Runtime** from Browse → Extensions.

Updating or uninstalling the runtime restarts Mihon. That is expected: Mihon has the runtime
loaded.

## Layout

```
contract/        the library extensions bundle to reach the runtime (keiyoushi.lib.runtime),
                 published on JitPack as com.github.keiyoushi.runtime:contract:<tag>
api/<name>/      a module's contract interfaces (published as api-<name>)
modules/<name>/  a module's implementation
host/            the APK: extension metadata, the extension's factory, the launcher screen
build-logic/     Gradle conventions: library, module, host, publishing, signing
```

`api/hello` and `modules/hello` are a test fixture: they check the module plumbing, and only
debug builds include them.

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
   through `RuntimeModule("<name>", api).bind<...>()`, as `modules/hello/test` does. A green
   bind means every contract function resolves to the module's implementation.
5. If the module bundles a library, add its license as `host/assets/licenses/<library>.txt`. The
   launcher screen lists it next to the runtime's own license.

The host picks up every `modules/*` project by itself and generates its registry from them, so
nothing in `host/` changes. Raise `versionCode` in `host/build.gradle.kts`.

## Build

```
./gradlew assembleRelease testDebugUnitTest lint spotlessCheck
```

Without a key the release APK is debug-signed. Release signing reads `KEYSTORE_PATH` or
`KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` from the environment. Build
with `--no-configuration-cache` when these are set.

## Release

1. Merge a PR that raises `versionCode`. CI checks that it exceeds the latest release's.
2. Push the tag `v<versionCode>`. `release.yml` builds and signs the APK, creates the GitHub
   Release with the APK and its SHA-256, and rewrites the `repo` branch (the update store:
   `index.pb` and `index.json`, whose apkUrl is the Release asset).
   JitPack builds the `contract` and `api-*` artifacts for the tag when someone first requests
   them.

The store refuses an APK signed with any key other than the one in
`.github/signing-key-fingerprint`. If the key changes, every user has to uninstall and reinstall.
