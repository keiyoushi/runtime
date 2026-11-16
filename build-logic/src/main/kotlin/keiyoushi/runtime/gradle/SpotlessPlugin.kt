package keiyoushi.runtime.gradle

import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** ktlint as keiyoushi/extensions-source runs it */
class SpotlessPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.diffplug.spotless")
        val ktlintVersion = libs.version("ktlint")
        extensions.configure<SpotlessExtension> {
            kotlin {
                target("src/**/*.kt", "test/**/*.kt")
                ktlint(ktlintVersion).editorConfigOverride(mapOf("max_line_length" to Int.MAX_VALUE))
                trimTrailingWhitespace()
                endWithNewline()
            }
            kotlinGradle {
                target("*.kts")
                ktlint(ktlintVersion)
            }
        }
    }
}
