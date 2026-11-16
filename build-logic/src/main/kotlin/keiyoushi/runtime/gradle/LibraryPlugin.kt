package keiyoushi.runtime.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * An Android library of the runtime: `contract`, an `api/<name>` or a `modules/<name>`. Whatever the
 * app already loads for every extension (Kotlin, coroutines, extensions-lib) is compileOnly.
 */
class LibraryPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("runtime.spotless")
        configureAndroid()

        extensions.configure<LibraryExtension> {
            androidResources.enable = false
            if (file("proguard-rules.pro").exists()) {
                defaultConfig.consumerProguardFiles("proguard-rules.pro")
            }
        }

        dependencies {
            add("compileOnly", libs.bundle("provided"))
            add("testImplementation", libs.bundle("provided"))
            add("testImplementation", libs.library("junit"))
            add("testImplementation", libs.library("coroutines-test"))
        }
    }
}
