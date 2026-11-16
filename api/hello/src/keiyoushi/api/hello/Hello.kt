package keiyoushi.api.hello

import keiyoushi.lib.runtime.RuntimeContract

/** TEST FIXTURE: the contract of the `hello` module, API 1 */
interface Hello : RuntimeContract {
    @Throws(Exception::class)
    fun greet(name: String): String
}
