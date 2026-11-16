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
        // builds-gated locator tests' assumeTrue instructs. When it is not set, leave the property unset on the
        // test JVM (never forward a blank) so those tests skip; the builds (real WebView `.so`s, ~150 MB each)
        // are never committed.
        unitTests.all { test ->
            System.getProperty("xrw.webview-builds")?.takeIf { it.isNotBlank() }?.let { test.systemProperty("xrw.webview-builds", it) }
        }
    }
}

dependencies {
    implementation(project(":api:webviewpatch"))
    compileOnly(libs.androidx.annotation)
}
