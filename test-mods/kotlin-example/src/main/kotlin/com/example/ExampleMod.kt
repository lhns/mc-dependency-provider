package com.example

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import net.neoforged.fml.common.Mod

/**
 * Entry point — discovered via FML's standard `@Mod` annotation scan and instantiated by
 * the provider's Kotlin entrypoint adapter (which picks up [INSTANCE], the Kotlin `object`
 * singleton field).
 *
 * The `init` block fires when the provider resolves `INSTANCE` on the mod's per-mod
 * ModClassLoader, so CI Tier-2 grep asserts the per-mod classloader was actually used
 * (kotlinx.coroutines is served from the provider's manifest, not NeoForge's classpath).
 */
@Mod("kotlin_example")
object ExampleMod {

    init {
        val greeting = runBlocking {
            delay(1)
            "mcdepprovider/kotlin-example loaded"
        }
        val coroutineClass = kotlinx.coroutines.CoroutineScope::class.java
        val coroutineLoaderId = System.identityHashCode(coroutineClass.classLoader)
        val coroutineClassId = System.identityHashCode(coroutineClass)
        println(
            "[mcdp-smoke] mod=kotlin_example lang=kotlin name=$greeting " +
                "coroutines.CoroutineScope.class.id=$coroutineClassId " +
                "coroutines.loader.id=$coroutineLoaderId"
        )
    }

    fun onLoad(): String = runBlocking {
        delay(1)
        "mcdepprovider/kotlin-example loaded"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println(onLoad())
    }
}
