package com.spotify.scio.jupyter;

import io.github.classgraph.ClassGraph;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.beam.runners.core.construction.resources.PipelineResourcesDetector;
import org.apache.beam.runners.core.construction.resources.PipelineResourcesOptions.ClasspathScanningResourcesDetectorFactory;
import org.apache.beam.runners.core.construction.resources.PipelineResourcesOptions.PipelineResourcesDetectorFactory;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.UnknownKeyFor;

public class JupyterResourcesDetector implements PipelineResourcesDetector {

  private final ClassGraph classGraph;

  public JupyterResourcesDetector(ClassGraph classGraph) {
    this.classGraph = classGraph;
  }

  @Override
  public @UnknownKeyFor @NonNull @Initialized List<@UnknownKeyFor @NonNull @Initialized String> detect(
      @UnknownKeyFor @NonNull @Initialized ClassLoader classLoader) {
    List<File> classpathContents =
        classGraph
            .disableNestedJarScanning()
            .addClassLoader(classLoader)
            .scan(1)
            .getClasspathFiles();

    /*
    return classpathContents.stream().map(File::getAbsolutePath)
        .filter(name -> !name.contains("-sources.jar"))
        .filter(name -> !name.contains("ammonite"))
        .filter(name -> !name.contains("almond"))
        .filter(name -> !name.contains("spark"))
        .filter(name -> !name.contains("case-app"))
        .filter(name -> !name.contains("coursier"))
        .filter(name -> !name.contains("launcher.jar"))
        .collect(Collectors.toList());
     */
    return new ArrayList<>();
  }

  public static class JupyterResourcesDetectorFactory implements PipelineResourcesDetector.Factory {

    public static JupyterResourcesDetectorFactory create() {
      return new JupyterResourcesDetectorFactory();
    }

    @Override
    public @UnknownKeyFor @NonNull @Initialized PipelineResourcesDetector getPipelineResourcesDetector() {
      return new JupyterResourcesDetector(new ClassGraph());
    }
  }
}
