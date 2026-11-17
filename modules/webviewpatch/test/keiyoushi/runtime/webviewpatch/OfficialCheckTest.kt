package keiyoushi.runtime.webviewpatch

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Official-channel WebView check for CI (`.github/workflows/webview-official.yml`). Runs the pure-Kotlin
 * [XrwLocator] against the `libwebviewchromium.so` of the current **Google-signed** Android System WebView
 * (Canary, Dev, Beta), and fails when the locator can no longer find the X-Requested-With adder — an early
 * warning that a new WebView would stop patching.
 *
 * The CI script (`webview-official-check.sh`) resolves each channel's version from chromiumdash, fetches the
 * Google-signed APK from the apkpure mirror by HTTP range, verifies the signer and Play source-stamp
 * certificate digests and the versionName (the mirror is untrusted transport; only Google's signature is
 * trusted), and lays each verified `.so` out under `-Dxrw.official-libs=<dir>` as
 * `<label>/libwebviewchromium.so`, with a `meta.tsv` of `label, channel, version, versionCode, abi, variant`.
 * Every laid-out `.so` is already verified, so every one must locate; a miss fails the job.
 *
 * Unset in a normal build, so this test skips (like the builds-gated locator tests). The WebView `.so`s are
 * far too large to commit; nothing here reads the oracle. Only the locator's structural match is exercised —
 * a pass means the shipped WebView of that version still carries a patchable adder.
 */
class OfficialCheckTest {

    private data class Row(
        val label: String,
        val channel: String,
        val version: String,
        val versionCode: String,
        val abi: String,
        val variant: String,
    )

    @Test
    fun locatorFindsTheAdderInEveryOfficialChannel() {
        val dir = System.getProperty("xrw.official-libs")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue("set -Dxrw.official-libs=<dir> (CI only)", dir != null)
        val rows = readMeta(File(dir, "meta.tsv"))
        assumeTrue("no verified official libs laid out", rows.isNotEmpty())

        val misses = rows.mapNotNull { row ->
            val so = File(File(dir, row.label), "libwebviewchromium.so")
            if (!so.isFile) return@mapNotNull "${row.label}: missing lib".also { println("OFFICIAL ${row.channel} ${row.version} ${row.versionCode} ${row.abi} (${row.variant}): MISSING") }
            val r = runCatching { XrwLocator.locate(so.readBytes()) }.getOrNull()
            val found = r?.adder != null
            val era = if (found) era(so) else "none"
            val adder = r?.adder?.let { "0x" + it.toString(16) } ?: "-"
            println("OFFICIAL ${row.channel} ${row.version} versionCode=${row.versionCode} ${row.abi} (${row.variant}): found=$found era=$era adder=$adder")
            if (!found) "${row.channel} ${row.version} ${row.abi} (${row.variant})" else null
        }
        assertEquals("the locator found no adder in these verified official WebView libs — a new WebView may not patch", emptyList<String>(), misses)
    }

    /** The [LocateStrategy] id that claims this build, for the per-row log. */
    private fun era(so: File): String {
        val elf = Elf(so.readBytes())
        return XrwLocator.strategies.firstNotNullOfOrNull { s -> s.locate(elf)?.takeIf { it.adder != null }?.let { s.id } } ?: "?"
    }

    private fun readMeta(tsv: File): List<Row> {
        if (!tsv.isFile) return emptyList()
        return tsv.readLines().filter { it.isNotBlank() }.map { it.split('\t') }.map {
            Row(it[0], it[1], it[2], it[3], it[4], it[5])
        }
    }
}
