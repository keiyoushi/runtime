package keiyoushi.runtime.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Base64

/**
 * Release signing from the environment, v2 + v3 (v3 so the key can be rotated later):
 * - `KEYSTORE_PATH`, or `KEYSTORE_B64` (the keystore, base64)
 * - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (defaults to the store password)
 *
 * Debug builds always use the default debug key. A release build without these fails when it
 * packages, unless `-Pruntime.debugSignRelease=true` (pr.yml) signs it with the debug key. Gradle
 * stores what configuration reads in the configuration cache, so build with
 * `--no-configuration-cache` when these are set.
 */
internal class Signing(private val project: Project) {
    private val env = project.providers
    private val path = env.environmentVariable("KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
    private val fromBase64 = path == null && !env.environmentVariable("KEYSTORE_B64").orNull.isNullOrBlank()
    private val decoded = project.layout.buildDirectory.file("signing/release.jks")
    private val debugSignRelease = env.gradleProperty("runtime.debugSignRelease").orNull == "true"
    private val storePassword = env.environmentVariable("KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotEmpty() }
    private val alias = env.environmentVariable("KEY_ALIAS").orNull?.takeIf { it.isNotEmpty() }

    /** Why release can't be signed, reported when a release task runs so sync and debug builds never need the key */
    private val problem: String? = when {
        debugSignRelease -> null
        path == null && !fromBase64 ->
            "Release builds need the signing key: set KEYSTORE_PATH (or KEYSTORE_B64), KEYSTORE_PASSWORD and KEY_ALIAS. " +
                "Build the debug variant to run locally, or pass -Pruntime.debugSignRelease=true to sign release with the debug key."
        storePassword == null || alias == null -> "Release signing needs KEYSTORE_PASSWORD and KEY_ALIAS next to KEYSTORE_PATH/KEYSTORE_B64"
        else -> null
    }

    fun configure(android: ApplicationExtension) {
        val release = android.buildTypes.getByName("release")
        if (debugSignRelease) {
            release.signingConfig = android.signingConfigs.getByName("debug")
            return
        }
        if (problem != null) return
        release.signingConfig = android.signingConfigs.create("release") {
            storeFile = path?.let(project::file) ?: decoded.get().asFile
            this.storePassword = this@Signing.storePassword
            keyAlias = alias
            keyPassword = env.environmentVariable("KEY_PASSWORD").orNull?.takeIf { it.isNotEmpty() } ?: this@Signing.storePassword
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    /** Writes `KEYSTORE_B64` to the build dir before anything reads the keystore, or fails release packaging with [problem] */
    fun wire() {
        val signingTasks = setOf("validateSigningRelease", "packageRelease", "signReleaseBundle")
        val message = problem
        if (message != null) {
            val fail = project.tasks.register("checkReleaseSigning") {
                doLast { throw GradleException(message) }
            }
            project.tasks.matching { it.name in signingTasks }.configureEach { dependsOn(fail) }
            return
        }
        if (debugSignRelease || !fromBase64) return
        val target = decoded
        val decode = project.tasks.register("decodeReleaseKeystore") {
            outputs.file(target)
            outputs.upToDateWhen { false }
            doLast {
                val file = target.get().asFile
                file.parentFile.mkdirs()
                file.writeBytes(Base64.getMimeDecoder().decode(System.getenv("KEYSTORE_B64").orEmpty()))
            }
        }
        project.tasks.matching { it.name in signingTasks }.configureEach { dependsOn(decode) }
    }
}
