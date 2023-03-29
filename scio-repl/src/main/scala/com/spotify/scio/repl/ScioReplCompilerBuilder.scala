package com.spotify.scio.repl

import ammonite.compiler.iface.{CompilerBuilder => ICompilerBuilder, CompilerLifecycleManager => ICompilerLifecycleManager}
import ammonite.compiler.{Compiler, CompilerBuilder, MakeReporter}
import ammonite.util.Frame

import java.net.URL
import java.nio.file.Path
import scala.collection.mutable
import scala.reflect.internal.util.{NoPosition, Position}
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.Settings
import scala.tools.nsc.io.File
import scala.tools.nsc.util.ClassPath

object ScioReplCompilerBuilder extends ICompilerBuilder {
  override def newManager(
    rtCacheDir: Option[Path], headFrame: => Frame, dependencyCompleter: => Option[String => (Int, Seq[String])], whiteList: Set[Seq[String]], initialClassLoader: ClassLoader): ICompilerLifecycleManager = {
    new ScioCompilerLifecycleManager(
      rtCacheDir,
      headFrame,
      dependencyCompleter,
      whiteList,
      initialClassLoader
    )
  }

  override def create(initialClassPath: Seq[URL], classPath: Seq[URL], dynamicClassPath: Seq[(String, Array[Byte])], evalClassLoader: ClassLoader, pluginClassLoader: ClassLoader, reporter: Option[ICompilerBuilder.Message => Unit], settings: Seq[String], classPathWhiteList: Set[Seq[String]], lineNumberModifier: Boolean): Compiler = {
    println("BUILDER")

    val vd = new VirtualDirectory("(memory)", None)
    Compiler.addToClasspath(dynamicClassPath, vd)

    val classes = ScioReplClassLoader
      .classLoaderURLs(Thread.currentThread.getContextClassLoader)
      .toSeq
      .concat(classPath)

    val fromSbt = Thread.currentThread.getStackTrace.exists { elem =>
      elem.getClassName.startsWith("sbt.Run")
    }

    val scioClassLoader = ScioReplClassLoader(classes.toArray)

    val scalacSettings = {
      // not 100% sure error collection is correct (duplicates?)
      val errors = new mutable.ListBuffer[String]
      val settings0 = new Settings(err => errors += err)
      val (_, unparsed) = settings0.processArguments(settings.toList, processAll = true)
      for (arg <- unparsed)
        errors += s"Unrecognized argument: $arg"

      val thisJar =
        this.getClass.getProtectionDomain.getCodeSource.getLocation.getPath

      ClassPath
        .split(settings0.classpath.value)
        .find(File(_).name.startsWith("paradise_"))
        .foreach(s => settings0.plugin.tryToSet(List(s)))

      settings0.embeddedDefaults(scioClassLoader)

      settings0.Yreploutdir.value = ""
      // Workaround for https://github.com/spotify/scio/issues/867
      settings0.Yreplclassbased.value = true
      // Force the repl to be synchronous, so all cmds are executed in the same thread
      settings0.Yreplsync.value = true
      // Set classloader chain - expose top level abstract class loader down
      // the chain to allow for readObject and latestUserDefinedLoader
      // See https://gist.github.com/harrah/404272
      settings0.embeddedDefaults(scioClassLoader)

      // TODO Report the errors via reporter?
      settings0.usejavacp.value = !fromSbt
      settings0.plugin.tryToSet(List(thisJar))
      settings0
    }

    val scalacReporterOpt = reporter.map { f =>
      def report(pos: Position, message: String, severity: String) = {
        val (start, end) =
          if (pos == NoPosition) (0, 0)
          else (pos.start, pos.end)
        val msg = ICompilerBuilder.Message(severity, start, end, message)
        f(msg)
      }

      MakeReporter.makeReporter(
        (pos, msg) => report(pos, msg, "ERROR"),
        (pos, msg) => report(pos, msg, "WARNING"),
        (pos, msg) => report(pos, msg, "INFO"),
        scalacSettings
      )
    }

    Compiler(
      classes,
      vd,
      scioClassLoader,
      scioClassLoader,
      () => (),
      scalacReporterOpt,
      scalacSettings,
      classPathWhiteList.map(_.toSeq).toSet,
      initialClassPath,
      lineNumberModifier
    )
  }

  override def scalaVersion: String = scala.util.Properties.versionNumberString

}
