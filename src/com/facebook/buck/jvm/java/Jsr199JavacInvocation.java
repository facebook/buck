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
import com.facebook.buck.jvm.core.HasJavaAbi;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.jvm.java.abi.SourceBasedAbiStubber;
import com.facebook.buck.jvm.java.abi.StubGenerator;
import com.facebook.buck.jvm.java.abi.source.api.FrontendOnlyJavacTaskProxy;
import com.facebook.buck.jvm.java.abi.source.api.SourceOnlyAbiRuleInfo;
import com.facebook.buck.jvm.java.abi.source.api.StopCompilation;
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
import com.facebook.buck.util.concurrent.MostExecutors.NamedThreadFactory;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.util.zip.JarBuilder;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import java.io.IOException;
import java.io.PrintWriter; // NOPMD required by API
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
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
  private static final ListeningExecutorService threadPool =
      MoreExecutors.listeningDecorator(
          Executors.newCachedThreadPool(new NamedThreadFactory("javac")));

  private final Supplier<JavaCompiler> compilerConstructor;
  private final JavacExecutionContext context;
  private final BuildTarget invokingRule;
  private final BuildTarget libraryTarget;
  private final AbiGenerationMode abiCompatibilityMode;
  private final ImmutableList<String> options;
  private final ImmutableList<JavacPluginJsr199Fields> pluginFields;
  private final ImmutableSortedSet<Path> javaSourceFilePaths;
  private final Path pathToSrcsList;
  private final AbiGenerationMode abiGenerationMode;
  @Nullable private final JarParameters abiJarParameters;
  @Nullable private final JarParameters libraryJarParameters;
  @Nullable private final SourceOnlyAbiRuleInfo ruleInfo;
  private final boolean trackClassUsage;

  @Nullable private CompilerWorker worker;

  public Jsr199JavacInvocation(
      Supplier<JavaCompiler> compilerConstructor,
      JavacExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableList<JavacPluginJsr199Fields> pluginFields,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      boolean trackClassUsage,
      @Nullable JarParameters abiJarParameters,
      @Nullable JarParameters libraryJarParameters,
      AbiGenerationMode abiGenerationMode,
      AbiGenerationMode abiCompatibilityMode,
      @Nullable SourceOnlyAbiRuleInfo ruleInfo) {
    this.compilerConstructor = compilerConstructor;
    this.context = context;
    this.invokingRule = invokingRule;
    this.libraryTarget =
        HasJavaAbi.isLibraryTarget(invokingRule)
            ? invokingRule
            : HasJavaAbi.getLibraryTarget(invokingRule);
    this.abiCompatibilityMode = abiCompatibilityMode;
    this.options = options;
    this.pluginFields = pluginFields;
    this.javaSourceFilePaths = javaSourceFilePaths;
    this.pathToSrcsList = pathToSrcsList;
    this.trackClassUsage = trackClassUsage;
    this.abiJarParameters = abiJarParameters;
    this.libraryJarParameters = libraryJarParameters;
    this.abiGenerationMode = abiGenerationMode;
    this.ruleInfo = ruleInfo;
  }

  @Override
  public int buildSourceOnlyAbiJar() throws InterruptedException {
    return getWorker().buildSourceOnlyAbiJar();
  }

  @Override
  public int buildSourceAbiJar() throws InterruptedException {
    return getWorker().buildSourceAbiJar();
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
                  .transform(Javac.ARGFILES_ESCAPER::apply),
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

    return getWorker().buildClasses();
  }

  private CompilerWorker getWorker() {
    if (worker == null) {
      worker = new CompilerWorker(threadPool);
    }

    return worker;
  }

  @Override
  public void close() {
    // Must close the worker first so that the compiler has a chance to exit.
    if (worker != null) {
      worker.close();
      worker = null;
    }
  }

  private class CompilerWorker implements AutoCloseable {
    private final ListeningExecutorService executor;

    private final SettableFuture<Integer> compilerResult = SettableFuture.create();
    private final SettableFuture<Boolean> shouldCompileFullJar = SettableFuture.create();
    private final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @Nullable private BuckJavacTaskProxy lazyJavacTask;

    /** A perf event that's used to show, on the background thread, what rule is being built. */
    @Nullable private JavacEventSinkScopedSimplePerfEvent targetEvent;

    @Nullable private String compilerThreadName;
    @Nullable private JavacPhaseEventLogger phaseEventLogger;
    @Nullable private JavaInMemoryFileManager inMemoryFileManager;
    @Nullable private ClassUsageTracker classUsageTracker;
    @Nullable private Jsr199TracingBridge tracingBridge;

    private CompilerWorker(ListeningExecutorService executor) {
      this.executor = executor;

      classUsageTracker = trackClassUsage ? new ClassUsageTracker() : null;
    }

    public int buildSourceOnlyAbiJar() throws InterruptedException {
      return buildAbiJar(true);
    }

    public int buildSourceAbiJar() throws InterruptedException {
      return buildAbiJar(false);
    }

    private int buildAbiJar(boolean buildSourceOnlyAbi) throws InterruptedException {
      SettableFuture<Integer> abiResult = SettableFuture.create();
      Futures.addCallback(
          compilerResult,
          new FutureCallback<Integer>() {
            @Override
            public void onSuccess(@Nullable Integer result) {
              abiResult.set(result);
            }

            @Override
            public void onFailure(Throwable t) {
              // Propagate failure
              abiResult.setException(t);
            }
          });

      BuildTarget abiTarget =
          buildSourceOnlyAbi
              ? HasJavaAbi.getSourceOnlyAbiJar(libraryTarget)
              : HasJavaAbi.getSourceAbiJar(libraryTarget);
      JarParameters jarParameters = Preconditions.checkNotNull(abiJarParameters);
      BuckJavacTaskProxy javacTask = getJavacTask(buildSourceOnlyAbi);
      javacTask.addPostEnterCallback(
          topLevelTypes -> {
            try {
              if (buildSuccessful()) {
                // Only attempt to build stubs if the build is successful so far; errors can
                // put javac into an unknown state.
                JarBuilder jarBuilder = newJarBuilder(jarParameters).setShouldHashEntries(true);
                StubGenerator stubGenerator =
                    new StubGenerator(
                        getTargetVersion(options),
                        javacTask.getElements(),
                        javacTask.getTypes(),
                        javacTask.getMessager(),
                        jarBuilder,
                        context.getEventSink(),
                        abiCompatibilityMode,
                        options.contains("-parameters"));
                stubGenerator.generate(topLevelTypes);
                jarBuilder.createJarFile(
                    context
                        .getProjectFilesystem()
                        .getPathForRelativePath(jarParameters.getJarPath()));
              }

              debugLogDiagnostics();
              if (buildSuccessful()) {
                if (classUsageTracker != null) {
                  new DefaultClassUsageFileWriter()
                      .writeFile(
                          classUsageTracker,
                          CompilerParameters.getDepFilePath(
                              abiTarget, context.getProjectFilesystem()),
                          context.getProjectFilesystem(),
                          context.getCellPathResolver());
                }
                abiResult.set(0);
              } else {
                reportDiagnosticsToUser();
                abiResult.set(1);
              }

              if (HasJavaAbi.isSourceAbiTarget(invokingRule)) {
                switchToFullJarIfRequested();
              }
            } catch (IOException e) {
              abiResult.setException(e);
            } catch (InterruptedException | ExecutionException e) {
              // These come from the get on shouldCompileFullJar, which should never throw
              throw new AssertionError(e);
            }
          });

      try {
        String threadName = startCompiler(javacTask);
        try (JavacEventSinkScopedSimplePerfEvent event =
            new JavacEventSinkScopedSimplePerfEvent(context.getEventSink(), threadName)) {
          return abiResult.get();
        }
      } catch (ExecutionException e) {
        Throwables.throwIfUnchecked(e.getCause());
        throw new HumanReadableException("Failed to generate abi: %s", e.getCause().getMessage());
      }
    }

    private void switchToFullJarIfRequested() throws InterruptedException, ExecutionException {
      Preconditions.checkNotNull(targetEvent).close();

      // Make a new event to capture the time spent waiting for the next stage in the
      // pipeline (or for the pipeline to realize it's done)
      targetEvent =
          new JavacEventSinkScopedSimplePerfEvent(context.getEventSink(), "Waiting for pipeline");
      if (!shouldCompileFullJar.get()) {
        // targetEvent will be closed in startCompiler
        throw new StopCompilation();
      }
      targetEvent.close();

      // Now start tracking the full jar
      Preconditions.checkNotNull(tracingBridge).setBuildTarget(libraryTarget);
      Preconditions.checkNotNull(phaseEventLogger).setBuildTarget(libraryTarget);
      targetEvent =
          new JavacEventSinkScopedSimplePerfEvent(context.getEventSink(), libraryTarget.toString());
    }

    public int buildClasses() throws InterruptedException {
      shouldCompileFullJar.set(true);
      try {
        String threadName = startCompiler(getJavacTask(false));
        try (JavacEventSinkScopedSimplePerfEvent event =
            new JavacEventSinkScopedSimplePerfEvent(context.getEventSink(), threadName)) {
          return compilerResult.get();
        }
      } catch (ExecutionException e) {
        Throwables.throwIfUnchecked(e.getCause());
        throw new HumanReadableException("Failed to compile: %s", e.getCause().getMessage());
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

    @Override
    public void close() {
      if (!compilerResult.isDone()) {
        // Make sure `.get()` won't hang forever if `compilerResult` is not initialized.
        // Note, `set` is no-op is `.setFuture` is already called.
        compilerResult.set(1);

        shouldCompileFullJar.set(false);
        try {
          compilerResult.get();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
          throw new AssertionError(e);
        }
      }
    }

    private String startCompiler(BuckJavacTaskProxy javacTask)
        throws ExecutionException, InterruptedException {
      if (compilerThreadName == null) {
        SettableFuture<String> threadName = SettableFuture.create();
        compilerResult.setFuture(
            executor.submit(
                () -> {
                  threadName.set(Thread.currentThread().getName());
                  tracingBridge = new Jsr199TracingBridge(context.getEventSink(), invokingRule);
                  BuckTracing.setCurrentThreadTracingInterfaceFromJsr199Javac(tracingBridge);
                  targetEvent =
                      new JavacEventSinkScopedSimplePerfEvent(
                          context.getEventSink(), invokingRule.toString());
                  try {
                    boolean success = javacTask.call();
                    if (javacTask instanceof FrontendOnlyJavacTaskProxy) {
                      if (success) {
                        return 0;
                      } else {
                        debugLogDiagnostics();
                        reportDiagnosticsToUser();
                        return 1;
                      }
                    }

                    debugLogDiagnostics();

                    if (success && buildSuccessful()) {
                      if (classUsageTracker != null) {
                        new DefaultClassUsageFileWriter()
                            .writeFile(
                                classUsageTracker,
                                CompilerParameters.getDepFilePath(
                                    libraryTarget, context.getProjectFilesystem()),
                                context.getProjectFilesystem(),
                                context.getCellPathResolver());
                      }
                    } else {
                      reportDiagnosticsToUser();
                      return 1;
                    }

                    if (libraryJarParameters == null) {
                      return 0;
                    }

                    return newJarBuilder(libraryJarParameters)
                        .createJarFile(
                            Preconditions.checkNotNull(
                                context
                                    .getProjectFilesystem()
                                    .getPathForRelativePath(libraryJarParameters.getJarPath())));
                  } catch (RuntimeException e) {
                    if (e.getCause() instanceof StopCompilation) {
                      return 0;
                    } else {
                      throw new BuckUncheckedExecutionException(
                          e.getCause() != null ? e.getCause() : e, "When running javac");
                    }
                  } finally {
                    for (AutoCloseable closeable : Lists.reverse(closeables)) {
                      try {
                        closeable.close();
                      } catch (Exception e) {
                        LOG.warn(e, "Unable to close %s; we may be leaking memory.", closeable);
                      }
                    }
                    targetEvent.close();
                  }
                }));

        compilerThreadName = threadName.get();
      }

      return compilerThreadName;
    }

    private BuckJavacTaskProxy getJavacTask(boolean generatingSourceOnlyAbi) {
      if (lazyJavacTask == null) {
        try {
          JavaCompiler compiler = compilerConstructor.get();

          StandardJavaFileManager standardFileManager =
              compiler.getStandardFileManager(null, null, null);
          addCloseable(standardFileManager);

          StandardJavaFileManager fileManager;
          if (libraryJarParameters != null) {
            Path directToJarPath =
                context
                    .getProjectFilesystem()
                    .getPathForRelativePath(libraryJarParameters.getJarPath());
            inMemoryFileManager =
                new JavaInMemoryFileManager(
                    standardFileManager,
                    directToJarPath,
                    libraryJarParameters.getRemoveEntryPredicate());
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
          Writer compilerOutputWriter =
              new PrintWriter(context.getStdErr()); // NOPMD required by API
          PluginClassLoaderFactory loaderFactory =
              PluginLoader.newFactory(context.getClassLoaderCache());

          ListenableFileManager wrappedFileManager = new ListenableFileManager(fileManager);
          if (classUsageTracker != null) {
            wrappedFileManager.addListener(classUsageTracker);
          }
          BuckJavacTaskProxy javacTask;
          if (generatingSourceOnlyAbi) {
            javacTask =
                FrontendOnlyJavacTaskProxy.getTask(
                    loaderFactory,
                    compiler,
                    compilerOutputWriter,
                    wrappedFileManager,
                    diagnostics,
                    options,
                    classNamesForAnnotationProcessing,
                    compilationUnits);
          } else {
            javacTask =
                BuckJavacTaskProxy.getTask(
                    loaderFactory,
                    compiler,
                    compilerOutputWriter,
                    wrappedFileManager,
                    diagnostics,
                    options,
                    classNamesForAnnotationProcessing,
                    compilationUnits);
          }

          PluginClassLoader pluginLoader = loaderFactory.getPluginClassLoader(javacTask);

          BuckJavacTaskListener taskListener = null;
          if (abiGenerationMode.checkForSourceOnlyAbiCompatibility()
              && !generatingSourceOnlyAbi
              && ruleInfo != null) {
            ruleInfo.setFileManager(fileManager);
            taskListener =
                SourceBasedAbiStubber.newValidatingTaskListener(
                    pluginLoader,
                    javacTask,
                    ruleInfo,
                    () ->
                        diagnostics
                                .getDiagnostics()
                                .stream()
                                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                                .count()
                            > 0,
                    abiGenerationMode.getDiagnosticKindForSourceOnlyAbiCompatibility());
          }

          phaseEventLogger = new JavacPhaseEventLogger(invokingRule, context.getEventSink());
          TranslatingJavacPhaseTracer tracer = new TranslatingJavacPhaseTracer(phaseEventLogger);
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
          lazyJavacTask = javacTask;
        } catch (IOException e) {
          LOG.error(e);
          throw new HumanReadableException("IOException during compilation: ", e.getMessage());
        }
      }

      return lazyJavacTask;
    }

    private JarBuilder newJarBuilder(JarParameters jarParameters) {
      JarBuilder jarBuilder = new JarBuilder();
      Preconditions.checkNotNull(inMemoryFileManager).writeToJar(jarBuilder);
      return jarBuilder
          .setObserver(new LoggingJarBuilderObserver(context.getEventSink()))
          .setEntriesToJar(
              jarParameters.getEntriesToJar().stream().map(context.getProjectFilesystem()::resolve))
          .setMainClass(jarParameters.getMainClass().orElse(null))
          .setManifestFile(jarParameters.getManifestFile().orElse(null))
          .setShouldMergeManifests(true)
          .setRemoveEntryPredicate(jarParameters.getRemoveEntryPredicate());
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
              entries.hasMoreElements(); ) {
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
