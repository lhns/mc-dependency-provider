package com.example

import cats.syntax.all.*
import io.circe.parser.*
import net.neoforged.fml.common.Mod

/**
 * Entry point — discovered by FML's standard `@Mod` annotation scan and instantiated by
 * mc-lib-provider's Scala entrypoint adapter on NeoForge. The only mclibprovider-specific
 * piece is `modLoader = "mclibprovider"` in `neoforge.mods.toml`; the entry shape itself
 * matches every NeoForge tutorial.
 *
 * Uses `cats.syntax.all.*` + `io.circe.parser.*` to exercise the per-mod classloader
 * path on cats-kernel (keyword package `cats.kernel.instances.byte`) and a transitive
 * graph that would break under JPMS in a shared classpath.
 */
@Mod("neoforge_example")
object ExampleMod {

  // Runs on object-class init — which is when mc-lib-provider's ScalaEntrypointAdapter
  // resolves the `MODULE$` singleton during NeoForge's loadMod call. Used by CI Tier-2 /
  // local smoke to confirm the provider actually invoked the mod through its own
  // classloader path (cats.Functor identity hash proves per-mod URLClassLoader).
  locally {
    val parsed = parse("""{"name": "mc-lib-provider", "platform": "neoforge"}""")
    val name = parsed
      .map(_.hcursor.get[String]("name").toOption.combineAll)
      .getOrElse("<parse-failed>")
    val catsFunctorClass = classOf[cats.Functor[List]]
    val catsLoaderId = System.identityHashCode(catsFunctorClass.getClassLoader)
    val catsClassId = System.identityHashCode(catsFunctorClass)
    println(
      s"[mclib-smoke] mod=neoforge_example name=$name " +
        s"cats.Functor.class.id=$catsClassId cats.loader.id=$catsLoaderId"
    )
  }

  def onLoad(): String = {
    val parsed = parse("""{"name": "mc-lib-provider", "platform": "neoforge"}""")
    parsed
      .map(_.hcursor.get[String]("name").toOption.combineAll)
      .getOrElse("<parse-failed>")
  }

  def main(args: Array[String]): Unit = println(onLoad())
}
