println("Hello world!")

val fromSbt = Thread.currentThread.getStackTrace.exists { elem =>
  elem.getClassName.startsWith("sbt.Run")
}
interp.configureCompiler(_.settings.usejavacp.value = !fromSbt)

interp.configureCompiler(_.settings.YmacroAnnotations.value = true)

println(repl.compiler.settings)