/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.kotlin;

import static com.google.common.collect.Iterables.transform;

import com.facebook.buck.core.build.execution.context.IsolatedExecutionContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.event.BuckTracingEventBusBridge;
import com.facebook.buck.event.api.BuckTracing;
import com.facebook.buck.jvm.core.BuildTargetValue;
import com.facebook.buck.jvm.java.javax.SynchronizedToolProvider;
import com.facebook.buck.util.ClassLoaderCache;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

public class JarBackedReflectedKotlinc implements Kotlinc {

  private static final String COMPILER_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler";
  private static final String EXIT_CODE_CLASS = "org.jetbrains.kotlin.cli.common.ExitCode";
  private static final KotlincVersion VERSION = ImmutableKotlincVersion.ofImpl("in memory");

  private static final Function<Path, URL> PATH_TO_URL =
      p -> {
        try {
          return p.toUri().toURL();
        } catch (MalformedURLException e) {
          throw new RuntimeException(e);
        }
      };

  @AddToRuleKey private final ImmutableSet<SourcePath> compilerClassPath;

  JarBackedReflectedKotlinc(ImmutableSet<SourcePath> compilerClassPath) {
    this.compilerClassPath = compilerClassPath;
  }

  @Override
  public KotlincVersion getVersion() {
    return VERSION;
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<RelPath> javaSourceFilePaths,
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
  public ImmutableList<String> getCommandPrefix(SourcePathResolverAdapter resolver) {
    throw new UnsupportedOperationException("In memory kotlinc may not be used externally");
  }

  @Override
  public int buildWithClasspath(
      IsolatedExecutionContext context,
      BuildTargetValue invokingRule,
      ImmutableList<String> options,
      ImmutableSortedSet<RelPath> kotlinSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      AbsPath ruleCellRoot,
      boolean withDownwardApi) {

    ImmutableList<Path> expandedSources;
    try {
      expandedSources =
          getExpandedSourcePaths(ruleCellRoot, kotlinSourceFilePaths, workingDirectory);
    } catch (Throwable throwable) {
      throwable.printStackTrace();
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule.getFullyQualifiedName(), workingDirectory);
    }

    ImmutableList<String> args =
        ImmutableList.<String>builder()
            .addAll(options)
            .addAll(transform(expandedSources, path -> ruleCellRoot.resolve(path).toString()))
            .build();

    try {
      BuckTracing.setCurrentThreadTracingInterface(
          new BuckTracingEventBusBridge(
              context.getIsolatedEventBus(), invokingRule.getFullyQualifiedName()));

      Object compilerShim = loadCompilerShim(context);

      Method compile = compilerShim.getClass().getMethod("exec", PrintStream.class, String[].class);

      Class<?> exitCodeClass = compilerShim.getClass().getClassLoader().loadClass(EXIT_CODE_CLASS);

      Method getCode = exitCodeClass.getMethod("getCode");

      try (UncloseablePrintStream stdErr = new UncloseablePrintStream(context.getStdErr())) {
        Object exitCode = compile.invoke(compilerShim, stdErr, args.toArray(new String[0]));

        return (Integer) getCode.invoke(exitCode);
      }

    } catch (IllegalAccessException
        | InvocationTargetException
        | IOException
        | NoSuchMethodException
        | ClassNotFoundException ex) {
      throw new RuntimeException(ex);
    } finally {
      BuckTracing.clearCurrentThreadTracingInterface();
    }
  }

  private Object loadCompilerShim(IsolatedExecutionContext context) throws IOException {
    ClassLoaderCache classLoaderCache = context.getClassLoaderCache();
    classLoaderCache.addRef();
    try {
      ClassLoader classLoader =
          classLoaderCache.getClassLoaderForClassPath(
              SynchronizedToolProvider.getSystemToolClassLoader(),
              ImmutableList.copyOf(
                  compilerClassPath.stream()
                      .map(
                          p ->
                              context
                                  .getRuleCellRoot()
                                  .resolve(((PathSourcePath) p).getRelativePath())
                                  .getPath())
                      .map(PATH_TO_URL)
                      .iterator()));

      return classLoader.loadClass(COMPILER_CLASS).newInstance();
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    } finally {
      classLoaderCache.close();
    }
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolverAdapter resolver) {
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
