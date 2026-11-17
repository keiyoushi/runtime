package keiyoushi.runtime.webviewpatch

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Chromium-snapshot smoke check for CI (`.github/workflows/webview-snapshot.yml`). Runs the pure-Kotlin
 * [XrwLocator] against the `libwebviewchromium.so` of the latest published Chromium **snapshot** WebView
 * and fails when the locator can no longer find the X-Requested-With adder — an early warning that a future
 * WebView may stop patching.
 *
 * The CI script lays the extracted `.so`s out in two directories and names them:
 *  - `-Dxrw.snapshot-libs=<dir>`: the gate. Every `libwebviewchromium.so` under it must locate (adder != null),
 *    else the test fails with the list that did not. The script fills it from the production-shaped
 *    `Android_Arm64` build (arm64-v8a primary + armeabi-v7a secondary — the same 64+32 ABI layout the real
 *    WebView ships).
 *  - `-Dxrw.snapshot-info-libs=<dir>` (optional): informational only, never fails. The script fills it from the
 *    standalone 32-bit `Android` build, whose non-production codegen can diverge from the shipped WebView
 *    (see work/keiyoushi-runtime/snapshot-check.md); a miss there is logged, not gated.
 *
 * Both are unset in a normal build, so this test skips (like the builds-gated locator tests). The snapshot
 * `.so`s are far too large to commit; nothing here reads the oracle.
 *
 * What a pass/fail means, and its limit, is documented in CONTRIBUTING.md and snapshot-check.md: the snapshot
 * is a non-official trunk build, so a pass is a smoke signal, not proof the Google-signed Play WebView patches.
 */
class SnapshotCheckTest {

    @Test
    fun locatorFindsTheAdderInTheLatestSnapshot() {
        val gate = dirProperty("xrw.snapshot-libs")
        assumeTrue("set -Dxrw.snapshot-libs=<dir> (CI only)", gate != null)

        dirProperty("xrw.snapshot-info-libs")?.let { info ->
            libsIn(info).forEach { so ->
                val r = runCatching { XrwLocator.locate(so.readBytes()) }.getOrNull()
                println("SNAPSHOT-INFO ${describe(so, r)} (non-gating)")
            }
        }

        val libs = libsIn(gate!!)
        assumeTrue("no libwebviewchromium.so under $gate", libs.isNotEmpty())
        val misses = libs.mapNotNull { so ->
            val r = runCatching { XrwLocator.locate(so.readBytes()) }.getOrNull()
            println("SNAPSHOT-GATE ${describe(so, r)}")
            if (r?.adder == null) rel(so) else null
        }
        assertEquals("the locator found no adder in these snapshot libs — a new WebView may not patch", emptyList<String>(), misses)
    }

    private fun describe(so: File, r: LocateResult?): String {
        val found = r?.adder != null
        val era = if (found) XrwLocator.strategies.firstNotNullOfOrNull { s -> runCatching { s.locate(Elf(so.readBytes())) }.getOrNull()?.takeIf { it.adder != null }?.let { s.id } } ?: "?" else "none"
        val adder = r?.adder?.let { "0x" + it.toString(16) } ?: "-"
        return "${rel(so)}: found=$found era=$era adder=$adder reason=${r?.reason ?: "-"}"
    }

    /** `<abi>/libwebviewchromium.so`, the ABI being the parent dir the CI script lays the lib under. */
    private fun rel(so: File): String = "${so.parentFile?.name}/${so.name}"

    private fun libsIn(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.name == "libwebviewchromium.so" }.sortedBy { it.path }.toList()

    private fun dirProperty(name: String): File? = System.getProperty(name)?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
}
