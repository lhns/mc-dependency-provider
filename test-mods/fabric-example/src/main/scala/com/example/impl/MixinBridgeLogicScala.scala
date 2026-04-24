package com.example.impl

import com.example.api.MixinBridgeLogic
import cats.syntax.all.*

import java.util.concurrent.atomic.AtomicLong

/**
 * Scala implementation of the Mixin bridge contract (#23 / ADR-0008).
 *
 * Loaded through the mod's own `ModClassLoader` via `McLibProvider.loadMixinImpl`.
 * Exercising `cats.syntax.*` here proves the impl linked against the mod's per-mod
 * cats jar, not any accidental shared version — the whole point of the bridge.
 *
 * Prints a `[mclib-smoke]` marker the first time it's called so CI can assert the
 * mixin actually dispatched (as opposed to silently never firing).
 */
class MixinBridgeLogicScala extends MixinBridgeLogic {
  private val count = new AtomicLong(0L)

  override def onServerTick(): Unit = {
    val n = count.incrementAndGet()
    if (n == 1L) {
      val combined = List("mixin", "bridge", "ok").combineAll
      println(s"[mclib-smoke] mod=fabric_example mixin_bridge=$combined first_tick=ok")
    }
  }

  override def invocationCount(): Long = count.get()
}
