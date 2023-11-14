package com.spotify.scio.jupyter

import ammonite.compiler.CompilerExtensions.CompilerReplAPIExtensions
import ammonite.interp.api.InterpAPI
import ammonite.repl.api.ReplAPI
import com.spotify.scio.jupyter.JupyterResourcesDetector.JupyterResourcesDetectorFactory
import com.spotify.scio.{ScioContext, ScioExecutionContext, ScioResult}
import org.apache.beam.runners.core.construction.resources.PipelineResourcesOptions
import org.apache.beam.sdk.options.PipelineOptions

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Path, Paths}
import java.util.jar.{JarEntry, JarOutputStream}
import scala.reflect.io.AbstractFile
import scala.util.{Failure, Success, Try}

class JupyterScioContext private[scio](
  options:PipelineOptions,
  artifacts: List[String],
  replJarPath: File
)(implicit
  interpApi: InterpAPI,
  replApi: ReplAPI
) extends ScioContext(options, List(replJarPath.toString)) {

  println("It's the latest!")
  println("******REPL")
  println(s"REPL Jar Path: $replJarPath")
  println("******ARTIFACTS START")
  artifacts.foreach(println)
  println("******ARTIFACTS END")


  Try(optionsAs[PipelineResourcesOptions]) match {
    case Success(resourceOptions) =>
      println("Setting resource detector")
      resourceOptions.setPipelineResourcesDetectorFactoryClass(classOf[JupyterResourcesDetectorFactory])
    case Failure(t) =>
      println("Can't make a pipelineresourcesoptions")
      throw t
  }

  override def run(): ScioExecutionContext = {
    createJar()
    super.run()
  }

  private def createJar(): File = {
    val jarStream = new JarOutputStream(new FileOutputStream(replJarPath))
    val dir = replApi.compiler.settings.outputDirs.getSingleOutput.get
    try {
      addVirtualDirectoryToJar(dir, "", jarStream)
      addClasspathDependenciesToJar(jarStream)
    }
    finally {
      jarStream.close()
    }
    replJarPath
  }

  private def addClasspathDependenciesToJar(stream: JarOutputStream) = {
    artifacts.foreach{ artifactPath =>
      stream.putNextEntry(new JarEntry(artifactPath))
      stream.write(Files.readAllBytes(Paths.get(artifactPath)))
      stream.closeEntry()
    }
    stream
  }

  private def addVirtualDirectoryToJar(
    dir: Iterable[AbstractFile],
    entryPath: String,
    jarStream: JarOutputStream
  ): Unit = dir.foreach { file =>
    if (file.isDirectory) {
      println(s"*****DIRECTORY $file")
      // Recursively descend into subdirectories, adjusting the package name as we do.
      val dirPath = entryPath + file.name + "/"
      jarStream.putNextEntry(new JarEntry(dirPath))
      jarStream.closeEntry()
      addVirtualDirectoryToJar(file, dirPath, jarStream)
    } else if (file.hasExtension("class")) {
      println(s"*****CLASS $file")
      // Add class files as an entry in the jar file and write the class to the jar.
      println(entryPath + file.name)
      jarStream.putNextEntry(new JarEntry(entryPath + file.name))
      jarStream.write(file.toByteArray)
      jarStream.closeEntry()
    }
  }
}

object JupyterScioContext {
  def apply(options:PipelineOptions)(
    implicit interpApi: InterpAPI,
    replApi: ReplAPI
  ):JupyterScioContext = {
    //TODO: doctor pipelineoptions to set instance of [[PipelineResourcesDetector]]

    new JupyterScioContext(
      options,
      replApi
        .sess
        .frames
        .flatMap(_.classpath)
        .distinct
        .map(_.getPath),
        nextReplJarPath().toFile
    )
  }

  def nextReplJarPath(prefix: String = "jupyter-scala-scio-", suffix: String = ".jar"): Path =
    Files.createTempFile(prefix, suffix)
}
