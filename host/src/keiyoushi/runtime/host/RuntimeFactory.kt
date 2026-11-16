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
        RuntimeHost.register(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            apis = BuildModules.NAMES.zip(BuildModules.APIS.toList()).toMap(),
            minApis = BuildModules.NAMES.zip(BuildModules.MIN_APIS.toList()).toMap(),
            entries = BuildModules.NAMES.zip(BuildModules.entries()).toMap(),
        )
    }

    override fun createSources(): List<Source> = emptyList()
}
