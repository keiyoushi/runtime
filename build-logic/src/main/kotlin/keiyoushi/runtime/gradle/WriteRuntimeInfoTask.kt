package keiyoushi.runtime.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** `outputs/runtime-info.json`: what .github/scripts/publish-store.py puts in the store index */
@CacheableTask
abstract class WriteRuntimeInfoTask : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    @get:Input
    abstract val extensionName: Property<String>

    @get:Input
    abstract val versionCode: Property<Int>

    @get:Input
    abstract val libVersion: Property<String>

    @get:Input
    abstract val sourceId: Property<Long>

    /** `name:api:minApi` */
    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val modules = modules.get().map(ModuleSpec::decode).sortedBy { it.name }
        val json = buildString {
            appendLine("{")
            appendLine("  \"packageName\": ${packageName.get().quoted()},")
            appendLine("  \"name\": ${extensionName.get().quoted()},")
            appendLine("  \"versionCode\": ${versionCode.get()},")
            appendLine("  \"versionName\": ${"${libVersion.get()}.${versionCode.get()}".quoted()},")
            appendLine("  \"extensionLib\": ${libVersion.get().quoted()},")
            appendLine("  \"source\": { \"id\": \"${sourceId.get()}\", \"name\": ${extensionName.get().quoted()}, \"lang\": \"all\" },")
            append("  \"modules\": {")
            append(modules.joinToString(",") { "\n    ${it.name.quoted()}: { \"api\": ${it.api}, \"minApi\": ${it.minApi} }" })
            appendLine(if (modules.isEmpty()) "}" else "\n  }")
            appendLine("}")
        }
        outputFile.get().asFile.apply { parentFile.mkdirs() }.writeText(json)
    }
}

private fun String.quoted() = "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""
