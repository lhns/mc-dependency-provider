package com.example

import cats.syntax.all.*
import io.circe.parser.*

/** Entry point — instantiated by the provider's Scala entrypoint adapter. */
object ExampleMod {

  def onLoad(): String = {
    val parsed = parse("""{"name": "mcdepprovider"}""")
    parsed.map(_.hcursor.get[String]("name").toOption.combineAll).getOrElse("<parse-failed>")
  }

  def main(args: Array[String]): Unit = println(onLoad())
}
