package keiyoushi.runtime.gradle

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * ```
 * runtimeModule {
 *     name = "example" // default: the directory name
 *     api = 2          // the API level this build serves
 *     minApi = 1       // the lowest API level it still serves (default 1)
 * }
 * ```
 * The module's entry point is the object `keiyoushi.runtime.<name>.Entry`, which implements the
 * module's contract interfaces from `api/<name>`.
 */
abstract class RuntimeModuleExtension {
    abstract val name: Property<String>
    abstract val api: Property<Int>
    abstract val minApi: Property<Int>

    /** Only in debug builds of the host: modules that exist to test the plumbing */
    abstract val testFixture: Property<Boolean>
}

internal val MODULE_NAME = Regex("[a-z][a-z0-9]*")

class ModulePlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        check(path.startsWith(":modules:")) { "$path: runtime.module is for modules/<name>" }
        pluginManager.apply("runtime.library")

        val module = extensions.create<RuntimeModuleExtension>("runtimeModule")
        module.name.convention(project.name)
        module.minApi.convention(1)
        module.testFixture.convention(false)

        extensions.configure<LibraryAndroidComponentsExtension> {
            finalizeDsl { android ->
                val name = module.name.get()
                check(MODULE_NAME.matches(name)) { "$path: module name '$name' must match $MODULE_NAME" }
                val api = module.api.orNull ?: error("$path: set runtimeModule { api = N }")
                val minApi = module.minApi.get()
                check(api >= 1 && minApi in 1..api) { "$path: need 1 <= minApi ($minApi) <= api ($api)" }
                check(file("src/keiyoushi/runtime/$name/Entry.kt").isFile) {
                    "$path: the entry point is object keiyoushi.runtime.$name.Entry in src/keiyoushi/runtime/$name/Entry.kt"
                }
                android.namespace = "keiyoushi.runtime.$name"
            }
        }

        dependencies {
            add("implementation", project(":contract"))
        }
    }
}
