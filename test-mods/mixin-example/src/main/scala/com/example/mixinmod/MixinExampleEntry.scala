package com.example.mixinmod

import net.fabricmc.api.ModInitializer

/**
 * Entry point for the mixin codegen smoke. The Scala adapter ("mcdepprovider") loads this
 * through the per-mod {@code ModClassLoader}, proving the mod boots end-to-end. The mixins
 * fire on first server tick — see SmokeLog for the markers CI greps for.
 */
object MixinExampleEntry extends ModInitializer {
  override def onInitialize(): Unit = {
    println("[mcdp-smoke] mod=mixin_example boot ok")
  }
}
