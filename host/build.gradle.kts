plugins {
    id("runtime.host")
}

runtimeHost {
    // Release tag v<versionCode>; raise it in every PR that changes what the APK ships
    versionCode = 1
}
