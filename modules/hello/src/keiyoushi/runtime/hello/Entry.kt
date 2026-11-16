package keiyoushi.runtime.hello

import keiyoushi.api.hello.Hello

/** TEST FIXTURE: serves `hello` API 1 */
object Entry : Hello {
    override fun greet(name: String): String = "Hello, $name"
}
