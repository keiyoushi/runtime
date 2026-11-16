// TEST FIXTURE: proves the module plumbing (registry generation, binding through the contract)
// without a real module. Only debug builds of the host include it.
plugins {
    id("runtime.module")
}

runtimeModule {
    name = "hello"
    api = 1
    testFixture = true
}

dependencies {
    implementation(project(":api:hello"))
}
