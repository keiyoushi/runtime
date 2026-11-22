package keiyoushi.runtime.host

import keiyoushi.lib.runtime.RuntimeContract
import keiyoushi.lib.runtime.RuntimeModule
import keiyoushi.lib.runtime.RuntimeUnavailableException
import keiyoushi.runtime.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** The registry generated from the modules reaches the process registration intact */
class RuntimeFactoryTest {
    interface Fake : RuntimeContract {
        @Throws(Exception::class)
        fun greet(name: String): String
    }

    interface Other : RuntimeContract {
        @Throws(Exception::class)
        fun missing(): Int
    }

    object FakeEntry : Fake {
        override fun greet(name: String) = "fake:$name"
    }

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

    @Test
    fun registeredModulesBindThroughTheContract() {
        registerModules(arrayOf("fake"), intArrayOf(2), intArrayOf(1), arrayOf(FakeEntry))

        assertEquals("fake:x", RuntimeModule("fake", 1).bind<Fake>().greet("x"))
        assertEquals("fake:x", RuntimeModule("fake", 2).bind<Fake>().greet("x"))
        assertThrows(RuntimeUnavailableException::class.java) { RuntimeModule("fake", 2).bind<Other>() }
        assertEquals(
            "Update Keiyoushi Runtime (installed ${BuildConfig.VERSION_NAME}; this source needs fake 3).",
            assertThrows(RuntimeUnavailableException::class.java) { RuntimeModule("fake", 3).bind<Fake>() }.message,
        )
    }

    private companion object {
        const val REGISTRATION = "keiyoushi.runtime.v1"
    }
}
