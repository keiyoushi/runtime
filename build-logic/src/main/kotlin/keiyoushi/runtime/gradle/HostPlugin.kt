package keiyoushi.runtime.gradle

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.VariantOutputConfiguration
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.security.MessageDigest

/**
 * ```
 * runtimeHost {
 *     versionCode = 2 // release tag v2; must increase with every release
 * }
 * ```
 */
abstract class RuntimeHostExtension {
    abstract val versionCode: Property<Int>

    /** The extensions-lib version the apps check; `versionName` is `<libVersion>.<versionCode>` */
    abstract val libVersion: Property<String>
    abstract val name: Property<String>
}

/**
 * The runtime APK. It bundles every `modules/<name>` project and generates their registry, so host
 * code never names a module. It is an extension to the apps (`tachiyomi.extension` feature and
 * metadata, no sources) and an app whose launcher screen shows the version, modules and licenses.
 */
class HostPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply("runtime.spotless")
        configureAndroid()

        val host = extensions.create<RuntimeHostExtension>("runtimeHost")
        host.libVersion.convention("1.6")
        host.name.convention("Keiyoushi Runtime")

        val modules = rootProject.subprojects
            .filter { it.path.startsWith(":modules:") }
            .sortedBy { it.path }
            .map { project ->
                evaluationDependsOn(project.path)
                val module = project.extensions.getByType<RuntimeModuleExtension>()
                ModuleSpec(
                    name = module.name.get(),
                    api = module.api.get(),
                    minApi = module.minApi.get(),
                    testFixture = module.testFixture.get(),
                ) to project
            }
        val duplicates = modules.groupBy { it.first.name }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "Modules declared more than once: $duplicates" }

        dependencies {
            add("implementation", project(":contract"))
            add("compileOnly", libs.bundle("provided"))
            modules.forEach { (spec, project) ->
                add(if (spec.testFixture) "debugImplementation" else "implementation", project(project.path))
            }
            add("testImplementation", libs.bundle("provided"))
            add("testImplementation", libs.library("junit"))
        }

        val storeUrl = providers.gradleProperty("runtime.storeUrl")
        val signing = Signing(this)

        extensions.configure<ApplicationExtension> {
            namespace = "keiyoushi.runtime"
            defaultConfig {
                applicationId = "keiyoushi.runtime"
                targetSdk = libs.version("android-sdk-target").toInt()
            }
            buildFeatures.buildConfig = true
            signing.configure(this)
            buildTypes.named("release") {
                isMinifyEnabled = true
                proguardFiles("proguard-rules.pro")
                @Suppress("UnstableApiUsage")
                vcsInfo.include = false
            }
            dependenciesInfo {
                includeInApk = false
                includeInBundle = false
            }
            packaging.resources.excludes.add("kotlin-tooling-metadata.json")
            lint.checkReleaseBuilds = false
        }

        extensions.configure<ApplicationAndroidComponentsExtension> {
            finalizeDsl { android ->
                val versionCode = host.versionCode.orNull ?: error("$path: set runtimeHost { versionCode = N }")
                check(versionCode >= 1) { "versionCode must be at least 1" }
                val versionName = "${host.libVersion.get()}.$versionCode"
                android.defaultConfig.versionCode = versionCode
                android.defaultConfig.versionName = versionName
                android.defaultConfig.manifestPlaceholders["extName"] = host.name.get()
                android.defaultConfig.manifestPlaceholders["libVersion"] = host.libVersion.get()
                android.defaultConfig.buildConfigField("String", "STORE_URL", "\"${storeUrl.get()}\"")
                android.defaultConfig.buildConfigField("String", "RUNTIME_NAME", "\"${host.name.get()}\"")
            }

            onVariants { variant ->
                val variantModules = modules.map { it.first }.filter { !it.testFixture || variant.buildType == "debug" }
                val generate = tasks.register<GenerateRegistryTask>("generate${variant.name.capitalized()}RuntimeRegistry") {
                    this.modules.set(variantModules.map { it.encode() })
                    licenses.from(layout.projectDirectory.dir("assets/licenses").asFileTree.matching { include("*.txt") })
                    outputDir.set(layout.buildDirectory.dir("generated/runtime/${variant.name}"))
                }
                variant.sources.java?.addGeneratedSourceDirectory(generate, GenerateRegistryTask::outputDir)

                val runtimeLicense = tasks.register<RuntimeLicenseTask>("generate${variant.name.capitalized()}RuntimeLicense") {
                    license.set(rootProject.layout.projectDirectory.file("LICENSE"))
                    outputDir.set(layout.buildDirectory.dir("generated/runtimeLicense/${variant.name}"))
                }
                variant.sources.assets?.addGeneratedSourceDirectory(runtimeLicense, RuntimeLicenseTask::outputDir)

                variant.outputs
                    .filter { it.outputType == VariantOutputConfiguration.OutputType.SINGLE }
                    .forEach { output ->
                        @Suppress("UnstableApiUsage")
                        output.outputFileName.set(host.libVersion.zip(host.versionCode) { lib, code -> "keiyoushi-runtime-v$lib.$code.apk" })
                    }

                if (variant.buildType == "release") {
                    val info = tasks.register<WriteRuntimeInfoTask>("writeRuntimeInfo") {
                        packageName.set("keiyoushi.runtime")
                        extensionName.set(host.name)
                        versionCode.set(host.versionCode)
                        libVersion.set(host.libVersion)
                        sourceId.set(host.name.map { computeSourceId(it, "all") })
                        this.modules.set(variantModules.map { it.encode() })
                        outputFile.set(layout.buildDirectory.file("outputs/runtime-info.json"))
                    }
                    tasks.matching { it.name == "assembleRelease" }.configureEach { dependsOn(info) }
                }
            }
        }

        signing.wire()
    }
}

internal data class ModuleSpec(val name: String, val api: Int, val minApi: Int, val testFixture: Boolean) {
    fun encode() = "$name:$api:$minApi"

    companion object {
        fun decode(value: String) = value.split(':').let { (name, api, minApi) -> ModuleSpec(name, api.toInt(), minApi.toInt(), false) }
    }
}

private fun String.capitalized() = replaceFirstChar { it.uppercase() }

/** The source ID the apps compute for a source, as keiyoushi's ExtensionPlugin does: the store index's placeholder row */
internal fun computeSourceId(name: String, lang: String, versionId: Int = 1): Long {
    val key = "${name.lowercase()}/$lang/$versionId"
    val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
    return (0..7).map { bytes[it].toLong() and 0xff }.reduce { acc, l -> (acc shl 8) or l } and Long.MAX_VALUE
}
