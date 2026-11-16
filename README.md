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

## Contributing

Building, testing and adding modules: [CONTRIBUTING.md](CONTRIBUTING.md).
