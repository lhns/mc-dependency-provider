package com.example_v2

import cats.syntax.all.*

/**
 * Second mod for the version-isolation smoke (#22).
 *
 * Prints the identity hashcodes of `cats.Functor.class` and its defining classloader.
 * Paired with fabric-example (which pins cats 2.13.0), CI greps the log for two
 * `[mclib-smoke]` lines and asserts the classloader IDs differ — proving ADR-0001.
 */
object ExampleModV2 {

  def onLoad(): String = {
    val greeting = List("mc-lib-provider", "v2").combineAll
    val catsFunctorClass = classOf[cats.Functor[List]]
    val catsLoaderId = System.identityHashCode(catsFunctorClass.getClassLoader)
    val catsClassId = System.identityHashCode(catsFunctorClass)
    println(
      s"[mclib-smoke] mod=fabric_example_v2 name=$greeting " +
        s"cats.Functor.class.id=$catsClassId cats.loader.id=$catsLoaderId"
    )
    greeting
  }

  def main(args: Array[String]): Unit = println(onLoad())
}
