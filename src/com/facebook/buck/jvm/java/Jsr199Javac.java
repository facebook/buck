/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckTracingEventBusBridge;
import com.facebook.buck.event.MissingSymbolEvent;
import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.tracing.TranslatingJavacPhaseTracer;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ClassLoaderCache;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;
import javax.annotation.processing.Processor;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;

/**
 * Command used to compile java libraries with a variety of ways to handle dependencies.
 */
public abstract class Jsr199Javac implements Javac {

  private static final Logger LOG = Logger.get(Jsr199Javac.class);
  private static final JavacVersion VERSION = JavacVersion.of("in memory");
  private static final StandardJavaFileManagerFactory DEFAULT_FILE_MANAGER_FACTORY =
      compiler -> compiler.getStandardFileManager(null, null, null);

  @Override
  public JavacVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "javac";
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory javac may not be used externally");
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory javac may not be used externally");
  }

  protected abstract JavaCompiler createCompiler(
      ExecutionContext context,
      SourcePathResolver resolver);

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<String> safeAnnotationProcessors,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      ClassUsageFileWriter usedClassesFileWriter,
      Optional<StandardJavaFileManagerFactory> fileManagerFactory) {
    JavaCompiler compiler = createCompiler(context, resolver);

    StandardJavaFileManager fileManager =
        fileManagerFactory.or(DEFAULT_FILE_MANAGER_FACTORY).create(compiler);
    try {
      Iterable<? extends JavaFileObject> compilationUnits;
      try {
        compilationUnits = createCompilationUnits(
            fileManager,
            filesystem.getAbsolutifier(),
            javaSourceFilePaths);
      } catch (IOException e) {
        LOG.warn(e, "Error building compilation units");
        return 1;
      }

      try {
        return buildWithClasspath(
            context,
            filesystem,
            invokingRule,
            options,
            safeAnnotationProcessors,
            javaSourceFilePaths,
            pathToSrcsList,
            compiler,
            usedClassesFileWriter,
            fileManager,
            compilationUnits);
      } finally {
        close(compilationUnits);
      }
    } finally {
      try {
        fileManager.close();
      } catch (IOException e) {
        LOG.warn(e, "Unable to close java filemanager. We may be leaking memory.");
      }
    }
  }

  private int buildWithClasspath(
      ExecutionContext context,
      ProjectFilesystem filesystem,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<String> safeAnnotationProcessors,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      JavaCompiler compiler,
      ClassUsageFileWriter usedClassesFileWriter,
      StandardJavaFileManager fileManager,
      Iterable<? extends JavaFileObject> compilationUnits) {
    // write javaSourceFilePaths to classes file
    // for buck user to have a list of all .java files to be compiled
    // since we do not print them out to console in case of error
    try {
      filesystem.writeLinesToPath(
          FluentIterable.from(javaSourceFilePaths)
              .transform(Functions.toStringFunction())
              .transform(ARGFILES_ESCAPER),
          pathToSrcsList);
    } catch (IOException e) {
      context.logError(
          e,
          "Cannot write list of .java files to compile to %s file! Terminating compilation.",
          pathToSrcsList);
      return 1;
    }


    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<String> classNamesForAnnotationProcessing = ImmutableList.of();
    Writer compilerOutputWriter = new PrintWriter(context.getStdErr());
    JavaCompiler.CompilationTask compilationTask = compiler.getTask(
        compilerOutputWriter,
        usedClassesFileWriter.wrapFileManager(fileManager),
        diagnostics,
        options,
        classNamesForAnnotationProcessing,
        compilationUnits);

    boolean isSuccess = false;
    BuckTracing.setCurrentThreadTracingInterfaceFromJsr199Javac(
        new BuckTracingEventBusBridge(context.getBuckEventBus(), invokingRule));
    try {
      try (
          // TranslatingJavacPhaseTracer is AutoCloseable so that it can detect the end of tracing
          // in some unusual situations
          TranslatingJavacPhaseTracer tracer = TranslatingJavacPhaseTracer.setupTracing(
              invokingRule,
              context.getClassLoaderCache(),
              context.getBuckEventBus(),
              compilationTask);

          // Ensure annotation processors are loaded from their own classloader. If we don't do
          // this, then the evidence suggests that they get one polluted with Buck's own classpath,
          // which means that libraries that have dependencies on different versions of Buck's deps
          // may choke with novel errors that don't occur on the command line.
          ProcessorBundle bundle = prepareProcessors(
              context.getBuckEventBus(),
              compiler.getClass().getClassLoader(),
              context.getClassLoaderCache(),
              safeAnnotationProcessors,
              invokingRule,
              options)) {
        compilationTask.setProcessors(bundle.processors);

        // Invoke the compilation and inspect the result.
        isSuccess = compilationTask.call();
      } catch (IOException e) {
        LOG.warn(e, "Unable to close annotation processor class loader. We may be leaking memory.");
      }
    } finally {
      // Clear the tracing interface so we have no chance of leaking it to code that shouldn't
      // be using it.
      BuckTracing.clearCurrentThreadTracingInterfaceFromJsr199Javac();
    }

    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
      LOG.debug("javac: %s", DiagnosticPrettyPrinter.format(diagnostic));
    }

    if (isSuccess) {
      usedClassesFileWriter.writeFile(filesystem, context.getObjectMapper());
      return 0;
    } else {
      if (context.getVerbosity().shouldPrintStandardInformation()) {
        int numErrors = 0;
        int numWarnings = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
          Diagnostic.Kind kind = diagnostic.getKind();
          if (kind == Diagnostic.Kind.ERROR) {
            ++numErrors;
            handleMissingSymbolError(invokingRule, diagnostic, context, filesystem);
          } else if (kind == Diagnostic.Kind.WARNING ||
              kind == Diagnostic.Kind.MANDATORY_WARNING) {
            ++numWarnings;
          }

          context.getStdErr().println(DiagnosticPrettyPrinter.format(diagnostic));
        }

        if (numErrors > 0 || numWarnings > 0) {
          context.getStdErr().printf("Errors: %d. Warnings: %d.\n", numErrors, numWarnings);
        }
      }
      return 1;
    }
  }

  private void close(Iterable<? extends JavaFileObject> compilationUnits) {
    for (JavaFileObject unit : compilationUnits) {
      if (unit instanceof Closeable) {
        try {
          ((Closeable) unit).close();
        } catch (IOException e) {
          LOG.warn(e, "Unable to close zipfile. We may be leaking memory.");
        }
      }
    }
  }

  private ProcessorBundle prepareProcessors(
      BuckEventBus buckEventBus,
      ClassLoader compilerClassLoader,
      ClassLoaderCache classLoaderCache,
      Set<String> safeAnnotationProcessors,
      BuildTarget target,
      List<String> options) {
    String processorClassPath = null;
    String processorNames = null;

    Iterator<String> iterator = options.iterator();
    while (iterator.hasNext()) {
      String curr = iterator.next();
      if ("-processorpath".equals(curr) && iterator.hasNext()) {
        processorClassPath = iterator.next();
      } else if ("-processor".equals(curr) && iterator.hasNext()) {
        processorNames = iterator.next();
      }
    }

    if (processorClassPath == null || processorNames == null) {
      return new ProcessorBundle();
    }

    Iterable<String> rawPaths = Splitter.on(File.pathSeparator)
        .omitEmptyStrings()
        .split(processorClassPath);
    URL[] urls = FluentIterable.from(rawPaths)
        .transform(
            pathRelativeToProjectRoot -> {
              try {
                return Paths.get(pathRelativeToProjectRoot).toUri().toURL();
              } catch (MalformedURLException e) {
                // The paths we're being given should have all been resolved from the file
                // system already. We'd need to be unfortunate to get here. Bubble up a runtime
                // exception.
                throw new RuntimeException(e);
              }
            })
        .toArray(URL.class);

    List<String> names = Splitter.on(",")
        .trimResults()
        .omitEmptyStrings()
        .splitToList(processorNames);

    ProcessorBundle processorBundle = new ProcessorBundle();
    setProcessorBundleClassLoader(
        names,
        urls,
        compilerClassLoader,
        classLoaderCache,
        safeAnnotationProcessors,
        target,
        processorBundle);


    for (String name : names) {
      try {
        LOG.debug("Loading %s from own classloader", name);

        Class<? extends Processor> aClass =
            Preconditions.checkNotNull(processorBundle.classLoader)
                .loadClass(name)
                .asSubclass(Processor.class);
        processorBundle.processors.add(
            new TracingProcessorWrapper(
                buckEventBus,
                target,
                aClass.newInstance()));
      } catch (ReflectiveOperationException e) {
        // If this happens, then the build is really in trouble. Better warn the user.
        throw new HumanReadableException(
            "%s: javac unable to load annotation processor: %s",
            target.getFullyQualifiedName(),
            name);
      }
    }

    return processorBundle;
  }

  @VisibleForTesting
  void setProcessorBundleClassLoader(
      List<String> processorNames,
      URL[] processorClasspath,
      ClassLoader baseClassLoader,
      ClassLoaderCache classLoaderCache,
      Set<String> safeAnnotationProcessors,
      BuildTarget target,
      ProcessorBundle processorBundle) {
    // We can avoid lots of overhead in large builds by reusing the same classloader for annotation
    // processors. However, some annotation processors use static variables in a way that assumes
    // there is only one instance running in the process at a time (or at all), and such annotation
    // processors would break running inside of Buck. So we default to creating a new ClassLoader
    // for each build rule, with an option to whitelist "safe" processors in .buckconfig.
    if (safeAnnotationProcessors.containsAll(processorNames)) {
      LOG.debug("Reusing class loaders for %s.", target);
      processorBundle.classLoader = (URLClassLoader) classLoaderCache.getClassLoaderForClassPath(
          baseClassLoader,
          ImmutableList.copyOf(processorClasspath));
      processorBundle.closeClassLoader = false;
    } else {
      final List<String> unsafeProcessors = new ArrayList<>();
      for (String name : processorNames) {
        if (safeAnnotationProcessors.contains(name)) {
          continue;
        }
        unsafeProcessors.add(name);
      }
      LOG.debug(
          "Creating new class loader for %s because the following processors are not marked safe " +
              "for multiple use in a single process: %s",
          target,
          Joiner.on(',').join(unsafeProcessors));
      processorBundle.classLoader = new URLClassLoader(
          processorClasspath,
          baseClassLoader);
      processorBundle.closeClassLoader = true;
    }
  }

  private Iterable<? extends JavaFileObject> createCompilationUnits(
      StandardJavaFileManager fileManager,
      Function<Path, Path> absolutifier,
      Set<Path> javaSourceFilePaths) throws IOException {
    List<JavaFileObject> compilationUnits = Lists.newArrayList();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".java")) {
        // For an ordinary .java file, create a corresponding JavaFileObject.
        Iterable<? extends JavaFileObject> javaFileObjects = fileManager.getJavaFileObjects(
            absolutifier.apply(path).toFile());
        compilationUnits.add(Iterables.getOnlyElement(javaFileObjects));
      } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
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

  private void handleMissingSymbolError(
      BuildTarget invokingRule,
      Diagnostic<? extends JavaFileObject> diagnostic,
      ExecutionContext context,
      ProjectFilesystem filesystem) {
    JavacErrorParser javacErrorParser = new JavacErrorParser(
        filesystem,
        context.getJavaPackageFinder());
    Optional<String> symbol = javacErrorParser.getMissingSymbolFromCompilerError(
        DiagnosticPrettyPrinter.format(diagnostic));
    if (!symbol.isPresent()) {
      // This error wasn't related to a missing symbol, as far as we can tell.
      return;
    }
    MissingSymbolEvent event = MissingSymbolEvent.create(
        invokingRule,
        symbol.get(),
        MissingSymbolEvent.SymbolType.Java);
    context.getBuckEventBus().post(event);
  }

  @VisibleForTesting
  static class ProcessorBundle implements Closeable {
    @Nullable
    public URLClassLoader classLoader;
    public boolean closeClassLoader;
    public List<Processor> processors = Lists.newArrayList();

    @Override
    public void close() throws IOException {
      if (closeClassLoader && classLoader != null) {
        classLoader.close();
        classLoader = null;
      }
    }
  }
}
