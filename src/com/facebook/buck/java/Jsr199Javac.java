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

package com.facebook.buck.java;

import com.facebook.buck.event.MissingSymbolEvent;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
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
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Command used to compile java libraries with a variety of ways to handle dependencies.
 * <p>
 * If {@code buildDependencies} is set to
 * {@link com.facebook.buck.rules.BuildDependencies#FIRST_ORDER_ONLY}, this class will invoke javac
 * using {@code declaredClasspathEntries} for the classpath. If {@code buildDependencies} is set to
 * {@link com.facebook.buck.rules.BuildDependencies#TRANSITIVE}, this class will invoke javac using
 * {@code transitiveClasspathEntries} for the classpath. If {@code buildDependencies} is set to
 * {@link com.facebook.buck.rules.BuildDependencies#WARN_ON_TRANSITIVE}, this class will first
 * compile using {@code declaredClasspathEntries}, and should that fail fall back to
 * {@code transitiveClasspathEntries} but warn the developer about which dependencies were in
 * the transitive classpath but not in the declared classpath.
 */
public class Jsr199Javac implements Javac {

  private static final Logger LOG = Logger.get(Jsr199Javac.class);

  @Override
  public String getDescription(
      ExecutionContext context,
      ImmutableList<String> options,
      ImmutableSet<Path> javaSourceFilePaths,
      Optional<Path> pathToSrcsList) {
    StringBuilder builder = new StringBuilder("javac ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");

    if (pathToSrcsList.isPresent()) {
      builder.append("@").append(pathToSrcsList.get());
    } else {
      Joiner.on(" ").appendTo(builder, javaSourceFilePaths);
    }

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "javac";
  }

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<Path> javaSourceFilePaths,
      Optional<Path> pathToSrcsList,
      Optional<Path> workingDirectory) {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    Preconditions.checkNotNull(
        compiler,
        "If using JRE instead of JDK, ToolProvider.getSystemJavaCompiler() may be null.");
    StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
    Iterable<? extends JavaFileObject> compilationUnits = ImmutableSet.of();
    try {
      compilationUnits = createCompilationUnits(
          fileManager,
          context.getProjectFilesystem().getAbsolutifier(),
          javaSourceFilePaths);
    } catch (IOException e) {
      close(fileManager, compilationUnits, null);
      e.printStackTrace(context.getStdErr());
      return 1;
    }

    if (pathToSrcsList.isPresent()) {
      // write javaSourceFilePaths to classes file
      // for buck user to have a list of all .java files to be compiled
      // since we do not print them out to console in case of error
      try {
        context.getProjectFilesystem().writeLinesToPath(
            FluentIterable.from(javaSourceFilePaths)
                .transform(Functions.toStringFunction())
                .transform(ARGFILES_ESCAPER),
            pathToSrcsList.get());
      } catch (IOException e) {
        close(fileManager, compilationUnits, null);
        context.logError(
            e,
            "Cannot write list of .java files to compile to %s file! Terminating compilation.",
            pathToSrcsList.get());
        return 1;
      }
    }

    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
    List<String> classNamesForAnnotationProcessing = ImmutableList.of();
    Writer compilerOutputWriter = new PrintWriter(context.getStdErr());
    JavaCompiler.CompilationTask compilationTask = compiler.getTask(
        compilerOutputWriter,
        fileManager,
        diagnostics,
        options,
        classNamesForAnnotationProcessing,
        compilationUnits);

    // Ensure annotation processors are loaded from their own classloader. If we don't do this,
    // then the evidence suggests that they get one polluted with Buck's own classpath, which
    // means that libraries that have dependencies on different versions of Buck's deps may choke
    // with novel errors that don't occur on the command line.
    ProcessorBundle bundle = null;
    boolean isSuccess;

    try {
      bundle = prepareProcessors(invokingRule, options);
      compilationTask.setProcessors(bundle.processors);

      // Invoke the compilation and inspect the result.
      isSuccess = compilationTask.call();
    } finally {
      close(fileManager, compilationUnits, bundle);
    }

    if (isSuccess) {
      return 0;
    } else {
      if (context.getVerbosity().shouldPrintStandardInformation()) {
        int numErrors = 0;
        int numWarnings = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
          Diagnostic.Kind kind = diagnostic.getKind();
          if (kind == Diagnostic.Kind.ERROR) {
            ++numErrors;
            handleMissingSymbolError(invokingRule, diagnostic, context);
          } else if (kind == Diagnostic.Kind.WARNING ||
              kind == Diagnostic.Kind.MANDATORY_WARNING) {
            ++numWarnings;
          }

          context.getStdErr().println(diagnostic);
        }

        if (numErrors > 0 || numWarnings > 0) {
          context.getStdErr().printf("Errors: %d. Warnings: %d.\n", numErrors, numWarnings);
        }
      }
      return 1;
    }
  }

  private void close(
      JavaFileManager fileManager,
      Iterable<? extends JavaFileObject> compilationUnits,
      @Nullable ProcessorBundle bundle) {
    try {
      fileManager.close();
    } catch (IOException e) {
      LOG.warn(e, "Unable to close java filemanager. We may be leaking memory.");
    }

    for (JavaFileObject unit : compilationUnits) {
      if (!(unit instanceof ZipEntryJavaFileObject)) {
        continue;
      }
      try {
        ((ZipEntryJavaFileObject) unit).close();
      } catch (IOException e) {
        LOG.warn(e, "Unable to close zipfile. We may be leaking memory.");
      }
    }

    if (bundle != null) {
      bundle.close();
    }
  }

  private ProcessorBundle prepareProcessors(BuildTarget target, List<String> options) {
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

    ProcessorBundle processorBundle = new ProcessorBundle();
    if (processorClassPath == null || processorNames == null) {
      return processorBundle;
    }

    Iterable<String> rawPaths = Splitter.on(File.pathSeparator)
        .omitEmptyStrings()
        .split(processorClassPath);
    URL[] urls = FluentIterable.from(rawPaths)
        .transform(
            new Function<String, URL>() {
              @Override
              public URL apply(String pathRelativeToProjectRoot) {
                try {
                  return Paths.get(pathRelativeToProjectRoot).toUri().toURL();
                } catch (MalformedURLException e) {
                  // The paths we're being given should have all been resolved from the file
                  // system already. We'd need to be unfortunate to get here. Bubble up a runtime
                  // exception.
                  throw new RuntimeException(e);
                }
              }
            })
        .toArray(URL.class);
    processorBundle.classLoader = new URLClassLoader(
        urls,
        ToolProvider.getSystemToolClassLoader());

    Iterable<String> names = Splitter.on(",")
        .trimResults()
        .omitEmptyStrings()
        .split(processorNames);
    for (String name : names) {
      try {
        LOG.debug("Loading %s from own classloader", name);

        Class<? extends Processor> aClass =
            Preconditions.checkNotNull(processorBundle.classLoader)
                .loadClass(name)
                .asSubclass(Processor.class);
        processorBundle.processors.add(aClass.newInstance());
      } catch (ReflectiveOperationException e) {
        processorBundle.close();
        // If this happens, then the build is really in trouble. Better warn the user.
        throw new HumanReadableException(
            "%s: javac unable to load annotation processor: %s",
            target != null ? target.getFullyQualifiedName() : "unknown target",
            name);
      }
    }

    return processorBundle;
  }

  private Iterable<? extends JavaFileObject> createCompilationUnits(
      StandardJavaFileManager fileManager,
      Function<Path, Path> absolutifier,
      Set<Path> javaSourceFilePaths) throws IOException {
    List<JavaFileObject> compilationUnits = Lists.newArrayList();
    for (Path path : javaSourceFilePaths) {
      if (path.toString().endsWith(".java")) {
        // For an ordinary .java file, create a corresponding JavaFileObject.
        Iterable<? extends JavaFileObject> javaFileObjects = fileManager.getJavaFileObjects(
            absolutifier.apply(path).toFile());
        compilationUnits.add(Iterables.getOnlyElement(javaFileObjects));
      } else if (path.toString().endsWith(SRC_ZIP)) {
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ZipFile zipFile = new ZipFile(absolutifier.apply(path).toFile());
        for (Enumeration<? extends ZipEntry> entries = zipFile.entries();
             entries.hasMoreElements();
            ) {
          ZipEntry entry = entries.nextElement();
          if (!entry.getName().endsWith(".java")) {
            continue;
          }

          compilationUnits.add(new ZipEntryJavaFileObject(zipFile, entry));
        }
      }
    }
    return compilationUnits;
  }

  private void handleMissingSymbolError(
      BuildTarget invokingRule,
      Diagnostic<? extends JavaFileObject> diagnostic,
      ExecutionContext context) {
    JavacErrorParser javacErrorParser = new JavacErrorParser(
        context.getProjectFilesystem(),
        context.getJavaPackageFinder());
    Optional<String> symbol =
        javacErrorParser.getMissingSymbolFromCompilerError(diagnostic.toString());
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

  private static class ProcessorBundle {
    @Nullable
    public URLClassLoader classLoader;
    public List<Processor> processors = Lists.newArrayList();

    public void close() {
      if (classLoader == null) {
        return;
      }

      try {
        classLoader.close();
      } catch (IOException e) {
        // Nothing sane to do. Log and carry on.
        LOG.warn("Unable to close annotation processor classloader.");
      } finally {
        // Null out the classloader to allow it to be garbage collected.
        classLoader = null;
      }
    }
  }
}
