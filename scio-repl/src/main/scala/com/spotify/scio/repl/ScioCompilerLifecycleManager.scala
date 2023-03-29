package com.spotify.scio.repl

import ammonite.compiler.iface.{Preprocessor, Compiler => ICompiler, CompilerBuilder => ICompilerBuilder}
import ammonite.compiler.{Compiler, CompilerLifecycleManager, MakeReporter, Pressy}
import ammonite.util.Util.ClassFiles
import ammonite.util.{Classpath, Printer}

import java.nio.file.Path
import scala.collection.mutable
import scala.reflect.internal.util.{NoPosition, Position}
import scala.reflect.io.VirtualDirectory
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.io.File
import scala.tools.nsc.util.ClassPath

class ScioCompilerLifecycleManager(
  rtCacheDir: Option[Path],
  headFrame: => ammonite.util.Frame,
  dependencyCompleteOpt: => Option[String => (Int, Seq[String])],
  classPathWhitelist: Set[Seq[String]],
  initialClassLoader: ClassLoader
) extends CompilerLifecycleManager(rtCacheDir, headFrame, dependencyCompleteOpt, classPathWhitelist, initialClassLoader) {

  private[this] object Internal {
    val dynamicClasspath = new VirtualDirectory("(memory)", None)
    var compiler: Compiler = null
    val onCompilerInit = mutable.Buffer.empty[scala.tools.nsc.Global => Unit]
    val onSettingsInit = mutable.Buffer.empty[scala.tools.nsc.Settings => Unit]
    var preConfiguredSettingsChanged: Boolean = false
    var pressy: Pressy = _
    var compilationCount = 0
    var (lastFrame, lastFrameVersion) = (headFrame, headFrame.version)
  }

  import Internal._


  override def init(force: Boolean = false) = synchronized {
    if (compiler == null ||
      (headFrame ne lastFrame) ||
      headFrame.version != lastFrameVersion ||
      Internal.preConfiguredSettingsChanged ||
      force) {

      lastFrame = headFrame
      lastFrameVersion = headFrame.version
      // Note we not only make a copy of `settings` to pass to the compiler,
      // we also make a *separate* copy to pass to the presentation compiler.
      // Otherwise activating autocomplete makes the presentation compiler mangle
      // the shared settings and makes the main compiler sad
      val settings = Option(compiler).fold(initialSettings())(_.compiler.settings.copy)
      onSettingsInit.foreach(_(settings))

      val initialClassPath = Classpath.classpath(initialClassLoader, rtCacheDir)
      val headFrameClassPath =
        Classpath.classpath(headFrame.classloader, rtCacheDir)

      val scalacReporterOpt:Option[MakeReporter.Reporter] = Some({
        def report(pos: Position, message: String, severity: String) = {
          val (start, end) =
            if (pos == NoPosition) (0, 0)
            else (pos.start, pos.end)
          val msg = ICompilerBuilder.Message(severity, start, end, message)
        }

        MakeReporter.makeReporter(
          (pos, msg) => report(pos, msg, "ERROR"),
          (pos, msg) => report(pos, msg, "WARNING"),
          (pos, msg) => report(pos, msg, "INFO"),
          settings
        )
      })

      Internal.compiler = Compiler(
        headFrameClassPath,
        dynamicClasspath,
        headFrame.classloader,
        headFrame.pluginClassloader,
        () => shutdownPressy(),
        None,
        settings,
        classPathWhitelist,
        initialClassPath
      )

      onCompilerInit.foreach(_(compiler.compiler))

      // Pressy is lazy, so the actual presentation compiler won't get instantiated
      // & initialized until one of the methods on it is actually used
      Internal.pressy = Pressy(
        headFrameClassPath,
        dynamicClasspath,
        headFrame.classloader,
        settings.copy(),
        dependencyCompleteOpt,
        classPathWhitelist,
        initialClassPath
      )

      Internal.preConfiguredSettingsChanged = false
    }
  }

  def initialSettings():Settings = {
    val settings = new Settings()
    val fromSbt = Thread.currentThread.getStackTrace.exists { elem =>
      elem.getClassName.startsWith("sbt.Run")
    }

    val thisJar =
      this.getClass.getProtectionDomain.getCodeSource.getLocation.getPath

    ClassPath
      .split(settings.classpath.value)
      .find(File(_).name.startsWith("paradise_"))
      .foreach(s => settings.plugin.tryToSet(List(s)))

    settings.embeddedDefaults(initialClassLoader)

    settings.Yreploutdir.value = ""
    // Workaround for https://github.com/spotify/scio/issues/867
    settings.Yreplclassbased.value = true
    // Force the repl to be synchronous, so all cmds are executed in the same thread
    settings.Yreplsync.value = true
    // Set classloader chain - expose top level abstract class loader down
    // the chain to allow for readObject and latestUserDefinedLoader
    // See https://gist.github.com/harrah/404272

    // TODO Report the errors via reporter?
    settings.usejavacp.value = !fromSbt
    settings.plugin.tryToSet(List(thisJar))
    settings
  }

  override def scalaVersion: String = super.scalaVersion

  override def compiler: Compiler = super.compiler

  override def compilationCount: Int = super.compilationCount

  override def pressy: Pressy = super.pressy

  override def preprocess(fileName: String): Preprocessor = super.preprocess(fileName)

  override def complete(offset: Int, previousImports: String, snippet: String): (Int, Seq[String], Seq[String]) = super.complete(offset, previousImports, snippet)

  override def compileClass(processed: Preprocessor.Output, printer: Printer, fileName: String) = super.compileClass(processed, printer, fileName)


  override def configureCompiler(callback: Global => Unit): Unit = super.configureCompiler(callback)

  override def preConfigureCompiler(callback: Settings => Unit): Unit = super.preConfigureCompiler(callback)

  override def addToClasspath(classFiles: ClassFiles): Unit = super.addToClasspath(classFiles)

  override def shutdownPressy(): Unit = super.shutdownPressy()
}
