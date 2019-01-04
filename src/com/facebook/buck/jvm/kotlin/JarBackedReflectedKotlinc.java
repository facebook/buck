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

package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class JarBackedReflectedKotlinc implements Kotlinc {

  private static final String COMPILER_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";
  private static final String EXIT_CODE_CLASS = "org.jetbrains.kotlin.cli.common.ExitCode";
  private static final KotlincVersion VERSION = KotlincVersion.of("in memory");

  private static final Function<Path, URL> PATH_TO_URL =
      p -> {
        try {
          return p.toUri().toURL();
        } catch (MalformedURLException e) {
          throw new RuntimeException(e);
        }
      };

  // Used to hang onto the KotlinDaemonShim for the life of the buckd process
  private static final Map<Set<String>, Object> kotlinShims = new ConcurrentHashMap<>();

  @AddToRuleKey private final ImmutableSet<SourcePath> compilerClassPath;
  private final Path annotationProcessingClassPath;
  private final Path standardLibraryClasspath;

  JarBackedReflectedKotlinc(
      ImmutableSet<SourcePath> compilerClassPath,
      Path annotationProcessingClassPath,
      Path standardLibraryClasspath) {
    this.compilerClassPath = compilerClassPath;
    this.annotationProcessingClassPath = annotationProcessingClassPath;
    this.standardLibraryClasspath = standardLibraryClasspath;
  }

  @Override
  public KotlincVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder("kotlinc ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return "kotlinc";
  }

  @Override
  public Path getAnnotationProcessorPath(SourcePathResolver sourcePathResolver) {
    return annotationProcessingClassPath;
  }

  @Override
  public Path getStdlibPath(SourcePathResolver sourcePathResolver) {
    return standardLibraryClasspath;
  }

  @Override
  public ImmutableList<Path> getAdditionalClasspathEntries(SourcePathResolver sourcePathResolver) {
    return ImmutableList.of(getStdlibPath(sourcePathResolver));
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory kotlinc may not be used externally");
  }

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSortedSet<Path> kotlinSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      ProjectFilesystem projectFilesystem) {

    ImmutableList<Path> expandedSources;
    try {
      expandedSources =
          getExpandedSourcePaths(
              projectFilesystem,
              context.getProjectFilesystemFactory(),
              kotlinSourceFilePaths,
              workingDirectory);
    } catch (Throwable throwable) {
      throwable.printStackTrace();
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s", invokingRule, workingDirectory);
    }

    ImmutableList<String> args =
        ImmutableList.<String>builder()
            .addAll(options)
            .addAll(
                transform(
                    expandedSources,
                    path -> projectFilesystem.resolve(path).toAbsolutePath().toString()))
            .build();

    Set<File> compilerIdPaths =
        compilerClassPath
            .stream()
            .map(p -> ((PathSourcePath) p).getRelativePath())
            .map(Path::toFile)
            .collect(Collectors.toSet());

    try {
      Object compilerShim =
          kotlinShims.computeIfAbsent(
              compilerIdPaths.stream().map(File::getAbsolutePath).collect(Collectors.toSet()),
              k -> loadCompilerShim(context));

      Method compile = compilerShim.getClass().getMethod("exec", PrintStream.class, String[].class);

      Class<?> exitCodeClass = compilerShim.getClass().getClassLoader().loadClass(EXIT_CODE_CLASS);

      Method getCode = exitCodeClass.getMethod("getCode");

      try (UncloseablePrintStream stdErr = new UncloseablePrintStream(context.getStdErr())) {
        Object exitCode = compile.invoke(compilerShim, stdErr, args.toArray(new String[0]));

        return (Integer) getCode.invoke(exitCode);
      }

    } catch (IllegalAccessException
        | InvocationTargetException
        | NoSuchMethodException
        | ClassNotFoundException ex) {
      throw new RuntimeException(ex);
    }
  }

  private Object loadCompilerShim(ExecutionContext context) {
    try {
      ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
      classLoaderCache.addRef();

      ClassLoader classLoader =
          classLoaderCache.getClassLoaderForClassPath(
              SynchronizedToolProvider.getSystemToolClassLoader(),
              ImmutableList.copyOf(
                  compilerClassPath
                      .stream()
                      .map(p -> ((PathSourcePath) p).getRelativePath())
                      .map(PATH_TO_URL)
                      .iterator()));

      return classLoader.loadClass(COMPILER_CLASS).newInstance();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    throw new UnsupportedOperationException("In memory kotlinc may not be used externally");
  }

  private static class UncloseablePrintStream extends PrintStream {
    UncloseablePrintStream(PrintStream delegate) {
      super(delegate);
    }

    @Override
    public void close() {
      // ignore
    }
  }
}
