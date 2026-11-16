package keiyoushi.runtime.gradle

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create

/**
 * Publishes the library's release AAR, for JitPack: `com.github.keiyoushi.runtime:<artifact>:<tag>`,
 * with `<artifact>` = `contract` or `api-<name>`. JitPack sets `VERSION` to the tag it builds.
 */
class PublishedPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("runtime.library")
        pluginManager.apply("maven-publish")

        group = "com.github.keiyoushi.runtime"
        version = providers.environmentVariable("VERSION").getOrElse("dev-SNAPSHOT")
        val artifact = path.removePrefix(":").replace(':', '-')

        extensions.configure<LibraryExtension> {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        afterEvaluate {
            extensions.configure<PublishingExtension> {
                publications.create<MavenPublication>("release") {
                    from(components.getByName("release"))
                    artifactId = artifact
                }
            }
        }
    }
}
