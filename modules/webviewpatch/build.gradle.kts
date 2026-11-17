plugins {
    id("runtime.module")
}

runtimeModule {
    name = "webviewpatch"
    api = 1
    minApi = 1
}

android {
    testOptions {
        // Forward `-Dxrw.webview-builds=<dir>` from the Gradle command line into the test JVM, as the
        // builds-gated locator tests' assumeTrue instructs. `xrw.official-libs` does the same for the
        // official-channel WebView check (OfficialCheckTest). When a property is not set, leave it unset on
        // the test JVM (never forward a blank) so those tests skip; the builds (real WebView `.so`s, ~150 MB
        // each) are never committed.
        unitTests.all { test ->
            listOf("xrw.webview-builds", "xrw.official-libs").forEach { prop ->
                System.getProperty(prop)?.takeIf { it.isNotBlank() }?.let { test.systemProperty(prop, it) }
            }
            // Surface the official-channel check's per-row log (OfficialCheckTest: channel, version,
            // versionCode, ABI, found/era) in the CI console, not just the HTML report.
            test.testLogging.showStandardStreams = true
        }
    }
}

dependencies {
    implementation(project(":api:webviewpatch"))
    compileOnly(libs.androidx.annotation)
}
