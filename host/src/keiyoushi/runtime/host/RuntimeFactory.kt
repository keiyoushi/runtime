package keiyoushi.runtime.host

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import keiyoushi.lib.runtime.RuntimeHost
import keiyoushi.runtime.BuildConfig

/**
 * The extension's entry point. Loading it makes this runtime's modules available to the other
 * extensions in the app. It has no sources: the version, modules and licenses are on the
 * launcher screen.
 */
class RuntimeFactory : SourceFactory {
    init {
        registerModules(BuildModules.NAMES, BuildModules.APIS, BuildModules.MIN_APIS, BuildModules.entries())
    }

    override fun createSources(): List<Source> = emptyList()
}

/** Registers the modules of a generated registry: parallel arrays, as `BuildModules` holds them */
internal fun registerModules(names: Array<String>, apis: IntArray, minApis: IntArray, entries: Array<Any>) = RuntimeHost.register(
    versionCode = BuildConfig.VERSION_CODE,
    versionName = BuildConfig.VERSION_NAME,
    apis = names.zip(apis.toList()).toMap(),
    minApis = names.zip(minApis.toList()).toMap(),
    entries = names.zip(entries).toMap(),
)
