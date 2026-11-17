plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.samWithReceiver)
    `java-gradle-plugin`
}

dependencies {
    compileOnly(gradleKotlinDsl())
    compileOnly(libs.android.gradle)
    compileOnly(libs.kotlin.gradle)
    implementation(libs.spotless.gradle)
    implementation(libs.tapmoc.gradle)
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

gradlePlugin {
    plugins {
        register("library") {
            id = "runtime.library"
            implementationClass = "keiyoushi.runtime.gradle.LibraryPlugin"
        }
        register("published") {
            id = "runtime.published"
            implementationClass = "keiyoushi.runtime.gradle.PublishedPlugin"
        }
        register("module") {
            id = "runtime.module"
            implementationClass = "keiyoushi.runtime.gradle.ModulePlugin"
        }
        register("host") {
            id = "runtime.host"
            implementationClass = "keiyoushi.runtime.gradle.HostPlugin"
        }
        register("spotless") {
            id = "runtime.spotless"
            implementationClass = "keiyoushi.runtime.gradle.SpotlessPlugin"
        }
    }
}
