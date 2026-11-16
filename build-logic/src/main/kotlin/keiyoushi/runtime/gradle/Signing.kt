package keiyoushi.runtime.gradle

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Project
import java.util.Base64

/**
 * Release signing from the environment, v2 + v3 (v3 so the key can be rotated later):
 * - `KEYSTORE_PATH`, or `KEYSTORE_B64` (the keystore, base64)
 * - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (defaults to the store password)
 *
 * Without a keystore the release build is debug-signed, unless `-Pruntime.requireSigning=true`
 * (release.yml). Gradle stores what configuration reads in the configuration cache, so build with
 * `--no-configuration-cache` when these are set.
 */
internal class Signing(private val project: Project) {
    private val env = project.providers
    private val path = env.environmentVariable("KEYSTORE_PATH").orNull?.takeIf { it.isNotBlank() }
    private val fromBase64 = path == null && !env.environmentVariable("KEYSTORE_B64").orNull.isNullOrBlank()
    private val decoded = project.layout.buildDirectory.file("signing/release.jks")
    private val required = env.gradleProperty("runtime.requireSigning").orNull == "true"

    fun configure(android: ApplicationExtension) {
        val release = android.buildTypes.getByName("release")
        if (path == null && !fromBase64) {
            check(!required) { "runtime.requireSigning: set KEYSTORE_PATH or KEYSTORE_B64, KEYSTORE_PASSWORD and KEY_ALIAS" }
            release.signingConfig = android.signingConfigs.getByName("debug")
            return
        }
        val storePassword = env.environmentVariable("KEYSTORE_PASSWORD").orNull
        val alias = env.environmentVariable("KEY_ALIAS").orNull
        check(!storePassword.isNullOrEmpty() && !alias.isNullOrEmpty()) { "Release signing needs KEYSTORE_PASSWORD and KEY_ALIAS" }
        release.signingConfig = android.signingConfigs.create("release") {
            storeFile = path?.let(project::file) ?: decoded.get().asFile
            this.storePassword = storePassword
            keyAlias = alias
            keyPassword = env.environmentVariable("KEY_PASSWORD").orNull?.takeIf { it.isNotEmpty() } ?: storePassword
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    /** Writes `KEYSTORE_B64` to the build dir before anything reads the keystore */
    fun wire() {
        if (!fromBase64) return
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
        project.tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }
            .configureEach { dependsOn(decode) }
    }
}
