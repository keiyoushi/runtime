package keiyoushi.runtime.host

import keiyoushi.runtime.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The registry generated from the modules reaches the process registration intact */
class RuntimeFactoryTest {
    @Before
    @After
    fun clear() {
        System.getProperties().remove(REGISTRATION)
    }

    @Test
    fun registersEveryModuleOnConstruction() {
        val sources = RuntimeFactory().createSources()

        val registration = System.getProperties()[REGISTRATION] as Map<*, *>
        assertEquals(BuildConfig.VERSION_CODE, registration["versionCode"])
        assertEquals(BuildModules.NAMES.zip(BuildModules.APIS.toList()).toMap(), registration["apis"])
        assertEquals(BuildModules.NAMES.zip(BuildModules.MIN_APIS.toList()).toMap(), registration["minApis"])
        val entries = registration["entries"] as Map<*, *>
        assertEquals(BuildModules.NAMES.toSet(), entries.keys)
        entries.forEach { (name, entry) -> assertEquals("keiyoushi.runtime.$name.Entry", entry!!.javaClass.name) }

        assertEquals(emptyList<Any>(), sources)
    }

    private companion object {
        const val REGISTRATION = "keiyoushi.runtime.v1"
    }
}
