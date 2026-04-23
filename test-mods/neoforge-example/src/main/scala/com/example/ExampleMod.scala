package com.example

import cats.syntax.all.*
import io.circe.parser.*

/**
 * Entry point — instantiated by mc-lib-provider's Scala entrypoint adapter on NeoForge.
 *
 * Uses `cats.syntax.all.*` + `io.circe.parser.*` to exercise the per-mod classloader
 * path on cats-kernel (keyword package `cats.kernel.instances.byte`) and a transitive
 * graph that would break under JPMS in a shared classpath.
 */
object ExampleMod {

  def onLoad(): String = {
    val parsed = parse("""{"name": "mc-lib-provider", "platform": "neoforge"}""")
    parsed
      .map(_.hcursor.get[String]("name").toOption.combineAll)
      .getOrElse("<parse-failed>")
  }

  def main(args: Array[String]): Unit = println(onLoad())
}
