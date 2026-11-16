package keiyoushi.runtime.gradle

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import tapmoc.configureJavaCompatibility

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

internal fun VersionCatalog.library(alias: String) = findLibrary(alias).get()

internal fun VersionCatalog.bundle(alias: String) = findBundle(alias).get()

/**
 * What every Android project here shares: SDK levels, Java level, and the flat keiyoushi layout
 * (`src/`, `test/`, `test-resources/`, `res/`, `assets/`, `AndroidManifest.xml` next to the build file).
 */
internal fun Project.configureAndroid() {
    configureJavaCompatibility(libs.version("java").toInt())

    extensions.configure<CommonExtension> {
        compileSdk = libs.version("android-sdk-compile").toInt()
        defaultConfig.minSdk = libs.version("android-sdk-min").toInt()

        sourceSets.named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.clear()
            java.directories.add("src")
            kotlin.directories.clear()
            kotlin.directories.add("src")
            res.directories.clear()
            res.directories.add("res")
            assets.directories.clear()
            assets.directories.add("assets")
        }
        sourceSets.named("test") {
            java.directories.clear()
            java.directories.add("test")
            kotlin.directories.clear()
            kotlin.directories.add("test")
            resources.directories.clear()
            resources.directories.add("test-resources")
        }

        testOptions.unitTests.isReturnDefaultValues = true
    }
}
