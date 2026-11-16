package keiyoushi.runtime.host

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import keiyoushi.lib.runtime.RuntimeHost
import keiyoushi.lib.runtime.RuntimeSource
import keiyoushi.runtime.BuildConfig

/**
 * The extension's entry point. Loading it makes this runtime's modules available to the other
 * extensions in the app; its one source only holds settings: version, modules and licenses.
 */
class RuntimeFactory : SourceFactory {
    private val apis = BuildModules.NAMES.zip(BuildModules.APIS.toList()).toMap()

    init {
        RuntimeHost.register(
            versionCode = BuildConfig.VERSION_CODE,
            versionName = BuildConfig.VERSION_NAME,
            apis = apis,
            minApis = BuildModules.NAMES.zip(BuildModules.MIN_APIS.toList()).toMap(),
            entries = BuildModules.NAMES.zip(BuildModules.entries()).toMap(),
        )
    }

    override fun createSources(): List<Source> = listOf(
        RuntimeSource(
            id = BuildConfig.SOURCE_ID,
            name = BuildConfig.SOURCE_NAME,
            versionName = BuildConfig.VERSION_NAME,
            apis = apis,
            licenses = BuildModules.LICENSE_NAMES.zip(BuildModules.LICENSE_PATHS).toMap(),
        ),
    )
}
