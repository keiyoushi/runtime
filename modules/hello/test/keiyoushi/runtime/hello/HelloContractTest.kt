package keiyoushi.runtime.hello

import keiyoushi.api.hello.Hello
import keiyoushi.lib.runtime.RuntimeHost
import keiyoushi.lib.runtime.RuntimeModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Binds the `hello` contract against this module's Entry the way an extension does at runtime: a
 * green bind() means every contract function resolves to an Entry function with the same name,
 * parameter types, return type and @Throws.
 */
class HelloContractTest {
    @Before
    @After
    fun clear() {
        System.getProperties().remove("keiyoushi.runtime.v1")
    }

    @Test
    fun entryServesTheContract() {
        RuntimeHost.register(
            versionCode = 1,
            versionName = "1.6.1",
            apis = mapOf("hello" to 1),
            minApis = mapOf("hello" to 1),
            entries = mapOf("hello" to Entry),
        )
        val hello = RuntimeModule("hello", api = 1).bind<Hello>()
        assertEquals("Hello, runtime", hello.greet("runtime"))
    }
}
