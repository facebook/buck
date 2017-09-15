/*
 * Copyright 2017-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.jvm.java.abi.SourceBasedAbiStubber;
import com.facebook.buck.jvm.java.abi.StubGenerator;
import com.facebook.buck.jvm.java.plugin.PluginLoader;
import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskListener;
import com.facebook.buck.jvm.java.plugin.api.BuckJavacTaskProxy;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoader;
import com.facebook.buck.jvm.java.plugin.api.PluginClassLoaderFactory;
import com.facebook.buck.jvm.java.tracing.JavacPhaseEventLogger;
import com.facebook.buck.jvm.java.tracing.TracingTaskListener;
import com.facebook.buck.jvm.java.tracing.TranslatingJavacPhaseTracer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.zip.JarBuilder;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import java.io.IOException;
import java.io.PrintWriter; // NOPMD required by API
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

class Jsr199JavacInvocation implements Javac.Invocation {
  private static final Logger LOG = Logger.get(Jsr199JavacInvocation.class);

  private final Function<JavacExecutionContext, JavaCompiler> compilerConstructor;
  private final JavacExecutionContext context;
  private final BuildTarget invokingRule;
  private final ImmutableList<String> options;
  private final ImmutableList<JavacPluginJsr199Fields> pluginFields;
  private final ImmutableSortedSet<Path> javaSourceFilePaths;
  private final Path pathToSrcsList;
  private final JavacCompilationMode compilationMode;
  private final boolean requiredForSourceAbi;
  private final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
  private final List<AutoCloseable> closeables = new ArrayList<>();

  @Nullable private BuckJavacTaskProxy javacTask;
  private boolean frontendRunAttempted = false;
  @Nullable private JavaInMemoryFileManager inMemoryFileManager;

  public Jsr199JavacInvocation(
      Function<JavacExecutionContext, JavaCompiler> compilerConstructor,
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      JavacCompilationMode compilationMode,
      boolean requiredForSourceAbi) {
    this.compilerConstructor = compilerConstructor;
    this.context = context;
    this.invokingRule = invokingRule;
    this.options = options;
    this.pluginFields = pluginFields;
    this.javaSourceFilePaths = javaSourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.compilationMode = compilationMode;
    this.requiredForSourceAbi = requiredForSourceAbi;
  }

  @Override
  public int buildSourceAbiJar(Path sourceAbiJar) throws InterruptedException {
    BuckTracing.setCurrentThreadTracingInterfaceFromJsr199Javac(
        new Jsr199TracingBridge(context.getEventSink(), invokingRule));
    try {
      // Invoke the compilation and inspect the result.
      BuckJavacTaskProxy javacTask = getJavacTask();

      javacTask.addPostEnterCallback(
          topLevelTypes -> {
            try {
              JarBuilder jarBuilder = newJarBuilder().setShouldHashEntries(true);
              StubGenerator stubGenerator =
                  new StubGenerator(
                      getTargetVersion(options),
                      javacTask.getElements(),
                      javacTask.getMessager(),
                      jarBuilder,
                      context.getEventSink());
              stubGenerator.generate(topLevelTypes);
              jarBuilder.createJarFile(sourceAbiJar);
            } catch (IOException e) {
              throw new HumanReadableException("Failed to generate abi: %s", e.getMessage());
            }
          });

      try {
        javacTask.parse();
        // JavacTask.call would stop between these phases if there were an error, so we do too.
        if (buildSuccessful()) {
          javacTask.enter();
        }
      } catch (RuntimeException e) {
        throw new HumanReadableException(
            String.format(
                "The compiler crashed when run without dependencies. There is probably an error in the source code. Try building %s to reveal it.",
                invokingRule.getUnflavoredBuildTarget().toString()),
            e);
      }
      frontendRunAttempted = true;

      debugLogDiagnostics();
      if (!buildSuccessful()) {
        reportDiagnosticsToUser();
        return 1;
      }

      return 0;
    } catch (IOException e) {
      LOG.error(e);
      throw new HumanReadableException("IOException during abi generation: ", e.getMessage());
    } finally {
      // Clear the tracing interface so we have no chance of leaking it to code that shouldn't
      // be using it.
      BuckTracing.clearCurrentThreadTracingInterfaceFromJsr199Javac();
    }
  }

  @Override
  public int buildClasses() throws InterruptedException {
    // write javaSourceFilePaths to classes file
    // for buck user to have a list of all .java files to be compiled
    // since we do not print them out to console in case of error
    try {
      context
          .getProjectFilesystem()
          .writeLinesToPath(
              FluentIterable.from(javaSourceFilePaths)
                  .transform(Object::toString)
                  .transform(Javac.ARGFILES_ESCAPER),
              pathToSrcsList);
    } catch (IOException e) {
      context
          .getEventSink()
          .reportThrowable(
              e,
              "Cannot write list of .java files to compile to %s file! Terminating compilation.",
              pathToSrcsList);
      return 1;
    }

    BuckTracing.setCurrentThreadTracingInterfaceFromJsr199Javac(
        new Jsr199TracingBridge(context.getEventSink(), invokingRule));
    try {
      invokeCompiler();

      debugLogDiagnostics();

      if (buildSuccessful()) {
        context
            .getUsedClassesFileWriter()
            .writeFile(context.getProjectFilesystem(), context.getCellPathResolver());
      } else {
        reportDiagnosticsToUser();
        return 1;
      }

      if (!context.getDirectToJarParameters().isPresent()) {
        return 0;
      }

      return newJarBuilder()
          .createJarFile(
              Preconditions.checkNotNull(
                  context
                      .getProjectFilesystem()
                      .getPathForRelativePath(
                          context.getDirectToJarParameters().get().getJarPath())));
    } catch (IOException e) {
      LOG.error(e);
      throw new HumanReadableException("IOException during compilation: ", e.getMessage());
    } finally {
      // Clear the tracing interface so we have no chance of leaking it to code that shouldn't
      // be using it.
      BuckTracing.clearCurrentThreadTracingInterfaceFromJsr199Javac();
    }
  }

  public void invokeCompiler() throws IOException {
    try {
      // Invoke the compilation and inspect the result.
      BuckJavacTaskProxy javacTask = getJavacTask();
      if (!frontendRunAttempted) {
        javacTask.parse();
        // JavacTask.call would stop between these phases if there were an error, so we do too.
        if (buildSuccessful()) {
          javacTask.enter();
        }
      }
      // JavacTask.generate will still try to run analyze even if enter failed, and in some cases
      // that can actually crash the compiler, so we make sure that it doesn't happen.
      if (buildSuccessful()) {
        javacTask.generate();
      }
    } catch (Throwable t) {
      // When invoking JavacTask.compile, javac itself catches all exceptions. (See the catch
      // blocks beginning at
      // http://hg.openjdk.java.net/jdk8u/jdk8u/langtools/file/9986bf97a48d/src/share/classes/com/sun/tools/javac/main/Main.java#l539)
      // This replicates some of that logic.
      switch (t.getClass().getName()) {
        case "java.io.IOException":
        case "java.lang.OutOfMemoryError":
        case "java.lang.StackOverflowError":
        case "com.sun.tools.javac.util.FatalError":
          Throwables.propagateIfPossible(t, IOException.class);
          throw new AssertionError("Should never get here.");
        case "com.sun.tools.javac.processing.AnnotationProcessingError":
        case "com.sun.tools.javac.util.ClientCodeException":
        case "com.sun.tools.javac.util.PropagatedException":
          Throwables.propagateIfPossible(t.getCause(), IOException.class);
          throw new RuntimeException(t.getCause());
        default:
          if (buildSuccessful()) {
            Throwables.propagateIfPossible(t, IOException.class);
            throw new RuntimeException(t);
          }

          // An error was already reported, so we need not do anything.
          return;
      }
    }
  }

  private void debugLogDiagnostics() {
    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
      LOG.debug("javac: %s", DiagnosticPrettyPrinter.format(diagnostic));
    }
  }

  private void reportDiagnosticsToUser() {
    if (context.getVerbosity().shouldPrintStandardInformation()) {
      List<Diagnostic<? extends JavaFileObject>> cleanDiagnostics =
          DiagnosticCleaner.clean(diagnostics.getDiagnostics());

      int numErrors = 0;
      int numWarnings = 0;
      for (Diagnostic<? extends JavaFileObject> diagnostic : cleanDiagnostics) {
        Diagnostic.Kind kind = diagnostic.getKind();
        if (kind == Diagnostic.Kind.ERROR) {
          ++numErrors;
        } else if (kind == Diagnostic.Kind.WARNING || kind == Diagnostic.Kind.MANDATORY_WARNING) {
          ++numWarnings;
        }

        context.getStdErr().println(DiagnosticPrettyPrinter.format(diagnostic));
      }

      if (numErrors > 0 || numWarnings > 0) {
        context.getStdErr().printf("Errors: %d. Warnings: %d.\n", numErrors, numWarnings);
      }
    }
  }

  private boolean buildSuccessful() {
    return diagnostics
            .getDiagnostics()
            .stream()
            .filter(diag -> diag.getKind() == Diagnostic.Kind.ERROR)
            .count()
        == 0;
  }

  private void addCloseable(Object maybeCloseable) {
    if (maybeCloseable instanceof AutoCloseable) {
      closeables.add((AutoCloseable) maybeCloseable);
    }
  }

  private BuckJavacTaskProxy getJavacTask() throws IOException {
    if (javacTask == null) {
      JavaCompiler compiler = compilerConstructor.apply(context);

      StandardJavaFileManager standardFileManager =
          compiler.getStandardFileManager(null, null, null);
      addCloseable(standardFileManager);

      StandardJavaFileManager fileManager;
      if (context.getDirectToJarParameters().isPresent()) {
        Path directToJarPath =
            context
                .getProjectFilesystem()
                .getPathForRelativePath(context.getDirectToJarParameters().get().getJarPath());
        inMemoryFileManager =
            new JavaInMemoryFileManager(
                standardFileManager,
                directToJarPath,
                context.getDirectToJarParameters().get().getRemoveEntryPredicate());
        addCloseable(inMemoryFileManager);
        fileManager = inMemoryFileManager;
      } else {
        inMemoryFileManager = null;
        fileManager = standardFileManager;
      }

      Iterable<? extends JavaFileObject> compilationUnits;
      try {
        compilationUnits =
            createCompilationUnits(
                fileManager, context.getProjectFilesystem()::resolve, javaSourceFilePaths);
        compilationUnits.forEach(this::addCloseable);
      } catch (IOException e) {
        LOG.warn(e, "Error building compilation units");
        throw e;
      }

      List<String> classNamesForAnnotationProcessing = ImmutableList.of();
      Writer compilerOutputWriter = new PrintWriter(context.getStdErr()); // NOPMD required by API
      PluginClassLoaderFactory loaderFactory =
          PluginLoader.newFactory(context.getClassLoaderCache());

      javacTask =
          BuckJavacTaskProxy.getTask(
              loaderFactory,
              compiler,
              compilerOutputWriter,
              context.getUsedClassesFileWriter().wrapFileManager(fileManager),
              diagnostics,
              options,
              classNamesForAnnotationProcessing,
              compilationUnits);

      PluginClassLoader pluginLoader = loaderFactory.getPluginClassLoader(javacTask);

      BuckJavacTaskListener taskListener = null;
      if (EnumSet.of(
              JavacCompilationMode.FULL_CHECKING_REFERENCES,
              JavacCompilationMode.FULL_ENFORCING_REFERENCES)
          .contains(compilationMode)) {
        taskListener =
            SourceBasedAbiStubber.newValidatingTaskListener(
                pluginLoader,
                javacTask,
                new DefaultInterfaceValidatorCallback(fileManager, requiredForSourceAbi),
                compilationMode == JavacCompilationMode.FULL_ENFORCING_REFERENCES
                    ? Diagnostic.Kind.ERROR
                    : Diagnostic.Kind.WARNING);
      }

      TranslatingJavacPhaseTracer tracer =
          new TranslatingJavacPhaseTracer(
              new JavacPhaseEventLogger(invokingRule, context.getEventSink()));
      // TranslatingJavacPhaseTracer is AutoCloseable so that it can detect the end of tracing
      // in some unusual situations
      addCloseable(tracer);

      // Ensure annotation processors are loaded from their own classloader. If we don't do
      // this, then the evidence suggests that they get one polluted with Buck's own classpath,
      // which means that libraries that have dependencies on different versions of Buck's deps
      // may choke with novel errors that don't occur on the command line.
      AnnotationProcessorFactory processorFactory =
          new AnnotationProcessorFactory(
              context.getEventSink(),
              compiler.getClass().getClassLoader(),
              context.getClassLoaderCache(),
              invokingRule);
      addCloseable(processorFactory);

      javacTask.setTaskListener(new TracingTaskListener(tracer, taskListener));
      javacTask.setProcessors(processorFactory.createProcessors(pluginFields));
    }
    return javacTask;
  }

  private JarBuilder newJarBuilder() throws IOException {
    JarBuilder jarBuilder = new JarBuilder();
    Preconditions.checkNotNull(inMemoryFileManager).writeToJar(jarBuilder);
    return jarBuilder
        .setObserver(new LoggingJarBuilderObserver(context.getEventSink()))
        .setEntriesToJar(
            context
                .getDirectToJarParameters()
                .get()
                .getEntriesToJar()
                .stream()
                .map(context.getProjectFilesystem()::resolve))
        .setMainClass(context.getDirectToJarParameters().get().getMainClass().orElse(null))
        .setManifestFile(context.getDirectToJarParameters().get().getManifestFile().orElse(null))
        .setShouldMergeManifests(true)
        .setRemoveEntryPredicate(
            context.getDirectToJarParameters().get().getRemoveEntryPredicate());
  }

  @Override
  public void close() {
    for (AutoCloseable closeable : Lists.reverse(closeables)) {
      try {
        closeable.close();
      } catch (Exception e) {
        LOG.warn(e, "Unable to close %s; we may be leaking memory.", closeable);
      }
    }
  }

  private Iterable<? extends JavaFileObject> createCompilationUnits(
      StandardJavaFileManager fileManager,
      Function<Path, Path> absolutifier,
      Set<Path> javaSourceFilePaths)
      throws IOException {
    List<JavaFileObject> compilationUnits = new ArrayList<>();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".java")) {
        // For an ordinary .java file, create a corresponding JavaFileObject.
        Iterable<? extends JavaFileObject> javaFileObjects =
            fileManager.getJavaFileObjects(absolutifier.apply(path).toFile());
        compilationUnits.add(Iterables.getOnlyElement(javaFileObjects));
      } else if (pathString.endsWith(Javac.SRC_ZIP) || pathString.endsWith(Javac.SRC_JAR)) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ZipFile zipFile = new ZipFile(absolutifier.apply(path).toFile());
        boolean hasZipFileBeenUsed = false;
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries();
            entries.hasMoreElements();
            ) {
          ZipEntry entry = entries.nextElement();
          if (!entry.getName().endsWith(".java")) {
            continue;
          }

          hasZipFileBeenUsed = true;
          compilationUnits.add(new ZipEntryJavaFileObject(zipFile, entry));
        }

        if (!hasZipFileBeenUsed) {
          zipFile.close();
        }
      }
    }
    return compilationUnits;
  }

  private static SourceVersion getTargetVersion(Iterable<String> options) {
    boolean foundTarget = false;
    for (String option : options) {
      if (option.equals("-target")) {
        foundTarget = true;
      } else if (foundTarget) {
        switch (option) {
          case "1.3":
            return SourceVersion.RELEASE_3;
          case "1.4":
            return SourceVersion.RELEASE_4;
          case "1.5":
          case "5":
            return SourceVersion.RELEASE_5;
          case "1.6":
          case "6":
            return SourceVersion.RELEASE_6;
          case "1.7":
          case "7":
            return SourceVersion.RELEASE_7;
          case "1.8":
          case "8":
            return SourceVersion.RELEASE_8;
          default:
            throw new HumanReadableException("target %s not supported", option);
        }
      }
    }

    throw new AssertionError("Unreachable code");
  }
}
