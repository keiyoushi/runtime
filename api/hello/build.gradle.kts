// TEST FIXTURE: the contract of modules/hello, which exists only to test the module plumbing.
// It isn't published; a real api/<name> applies runtime.published instead of runtime.library.
plugins {
    id("runtime.library")
}

android {
    namespace = "keiyoushi.api.hello"
}

dependencies {
    api(project(":contract"))
}
