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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public class JarBackedReflectedKotlinc implements Kotlinc {

  private static final String COMPILER_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";
  private static final String EXIT_CODE_CLASS = "org.jetbrains.kotlin.cli.common.ExitCode";

  private static final String FOLDER_PREFIX = "libexec/lib";

  private static final String KOTLIN_ANNOTATION_PROCESSING =
      "kotlin-annotation-processing-gradle.jar";

  private static final String KOTLIN_COMPILER_EMBEDDABLE = "kotlin-compiler-embeddable.jar";
  private static final String KOTLIN_REFLECT = "kotlin-reflect.jar";
  private static final String KOTLIN_SCRIPT_RUNTIME = "kotlin-script-runtime.jar";
  private static final String KOTLIN_STDLIB = "kotlin-stdlib.jar";

  private static final ImmutableList<String> HOME_LIBRARIES_JAR =
      ImmutableList.of(
          KOTLIN_COMPILER_EMBEDDABLE, KOTLIN_REFLECT, KOTLIN_SCRIPT_RUNTIME, KOTLIN_STDLIB);

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

  @AddToRuleKey private final SourcePath kotlinHome;

  JarBackedReflectedKotlinc(SourcePath kotlinHome) {
    this.kotlinHome = kotlinHome;
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
    return sourcePathResolver
        .getAbsolutePath(this.kotlinHome)
        .resolve(FOLDER_PREFIX)
        .resolve(KOTLIN_ANNOTATION_PROCESSING);
  }

  @Override
  public Path getStdlibPath(SourcePathResolver sourcePathResolver) {
    return sourcePathResolver
        .getAbsolutePath(this.kotlinHome)
        .resolve(FOLDER_PREFIX)
        .resolve(KOTLIN_STDLIB);
  }

  @Override
  public ImmutableList<Path> getHomeLibraries(SourcePathResolver sourcePathResolver) {
    return HOME_LIBRARIES_JAR
        .stream()
        .map(
            jar ->
                sourcePathResolver
                    .getAbsolutePath(this.kotlinHome)
                    .resolve(FOLDER_PREFIX)
                    .resolve(jar))
        .collect(ImmutableList.toImmutableList());
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
  public KotlincVersion getVersion() {
    throw new UnsupportedOperationException("In memory kotlinc doesn't have a version");
  }

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      BuildTarget invokingRule,
      ImmutableList<Path> kotlinHomeLibraries,
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
                expandedSources
                    .stream()
                    .map(path -> projectFilesystem.resolve(path).toAbsolutePath().toString())
                    .collect(Collectors.toList()))
            .build();

    Set<String> kotlinHomeLibrariesStringPaths =
        kotlinHomeLibraries
            .stream()
            .map(Path::toAbsolutePath)
            .map(Path::toString)
            .collect(Collectors.toSet());

    try {
      Object compilerShim =
          kotlinShims.computeIfAbsent(
              kotlinHomeLibrariesStringPaths, k -> loadCompilerShim(context, kotlinHomeLibraries));

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

  private Object loadCompilerShim(ExecutionContext context, List<Path> kotlinHomeLibraries) {
    try {
      ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
      classLoaderCache.addRef();

      ClassLoader classLoader =
          classLoaderCache.getClassLoaderForClassPath(
              SynchronizedToolProvider.getSystemToolClassLoader(),
              ImmutableList.copyOf(kotlinHomeLibraries.stream().map(PATH_TO_URL).iterator()));

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
