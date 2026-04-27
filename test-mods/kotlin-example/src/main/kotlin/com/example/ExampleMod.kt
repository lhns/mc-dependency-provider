package com.example

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod

/**
 * Entry point — discovered via FML's standard `@Mod` annotation scan and instantiated by
 * mcdpprovider's Kotlin entrypoint adapter, which now delegates to the bag-based ctor matcher
 * for plain classes (this file is one). The constructor takes the full vanilla-shaped bag —
 * `(IEventBus, ModContainer, Dist)` — to verify ADR-0017 plumbing in dev mode.
 *
 * The smoke print in the `init` block is grepped by CI Tier-2 to confirm:
 *  - `container.modId=kotlin_example` — McdpModContainer reached the ctor as `this`
 *  - `dist=...` — FMLEnvironment.dist plumbed through
 *  - `coroutines.loader.id=...` — kotlinx-coroutines is served via the per-mod ModClassLoader
 */
@Mod("kotlin_example")
class ExampleMod(
    private val eventBus: IEventBus,
    private val modContainer: ModContainer,
    private val dist: Dist,
) {

    init {
        val greeting = runBlocking {
            delay(1)
            "mcdepprovider/kotlin-example loaded"
        }
        val coroutineClass = kotlinx.coroutines.CoroutineScope::class.java
        val coroutineLoaderId = System.identityHashCode(coroutineClass.classLoader)
        val coroutineClassId = System.identityHashCode(coroutineClass)
        val busId = System.identityHashCode(eventBus)
        println(
            "[mcdp-smoke] mod=kotlin_example lang=kotlin name=$greeting " +
                "container.modId=${modContainer.modId} dist=${dist.name} " +
                "bus.id=$busId " +
                "coroutines.CoroutineScope.class.id=$coroutineClassId " +
                "coroutines.loader.id=$coroutineLoaderId"
        )
    }

    fun onLoad(): String = runBlocking {
        delay(1)
        "mcdepprovider/kotlin-example loaded"
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            // Standalone main path doesn't have a real FML environment — the constructor would
            // require an IEventBus + ModContainer + Dist. Keep the standalone path to a stub line
            // so `./gradlew :test-mods/kotlin-example:run` (if ever wired) still emits something.
            println("mcdepprovider/kotlin-example main entry (no FML environment)")
        }
    }
}
