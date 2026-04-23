package com.example

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay

/**
 * Entry point — instantiated by the provider's Kotlin entrypoint adapter, which picks up
 * [INSTANCE] (Kotlin `object` singleton field).
 */
object ExampleMod {

    fun onLoad(): String = runBlocking {
        delay(1)
        "mc-lib-provider/kotlin-example loaded"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println(onLoad())
    }
}
