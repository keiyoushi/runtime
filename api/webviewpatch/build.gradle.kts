plugins {
    id("runtime.published")
}

android {
    namespace = "keiyoushi.api.webviewpatch"
}

dependencies {
    api(project(":contract"))
}
