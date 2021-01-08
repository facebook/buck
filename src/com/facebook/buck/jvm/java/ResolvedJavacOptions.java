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

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.filesystems.AbsPath;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.javacd.model.ResolvedJavacOptions.JavacPluginJsr199Fields;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolved JavacOptions used in {@link JavacPipelineState} */
@BuckStyleValue
public abstract class ResolvedJavacOptions {

  public abstract Optional<String> getBootclasspath();

  public abstract Optional<List<RelPath>> getBootclasspathList();

  public abstract JavacLanguageLevelOptions getLanguageLevelOptions();

  public abstract boolean isDebug();

  public abstract boolean isVerbose();

  public abstract JavacPluginParams getJavaAnnotationProcessorParams();

  public abstract JavacPluginParams getStandardJavacPluginParams();

  public abstract List<String> getExtraArguments();

  public abstract ImmutableList<JavacPluginJsr199Fields> getAnnotationProcessors();

  public abstract ImmutableList<JavacPluginJsr199Fields> getJavaPlugins();

  public abstract boolean isJavaAnnotationProcessorParamsPresent();

  /** Creates {@link ResolvedJavacOptions} */
  public static ResolvedJavacOptions of(
      JavacOptions javacOptions, SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {

    JavacLanguageLevelOptions languageLevelOptions = javacOptions.getLanguageLevelOptions();
    ImmutableList<PathSourcePath> bootclasspath =
        javacOptions.getSourceToBootclasspath().get(languageLevelOptions.getSourceLevel());
    Optional<List<RelPath>> bootclasspathList = Optional.empty();
    if (bootclasspath != null) {
      bootclasspathList =
          Optional.of(
              bootclasspath.stream()
                  .map(resolver::getAbsolutePath)
                  .map(ruleCellRoot::relativize)
                  .collect(Collectors.toList()));
    }

    JavacPluginParams javaAnnotationProcessorParams =
        javacOptions.getJavaAnnotationProcessorParams();
    JavacPluginParams standardJavacPluginParams = javacOptions.getStandardJavacPluginParams();

    return of(
        javacOptions.getBootclasspath(),
        bootclasspathList,
        languageLevelOptions,
        javacOptions.isDebug(),
        javacOptions.isVerbose(),
        javaAnnotationProcessorParams,
        standardJavacPluginParams,
        javacOptions.getExtraArguments(),
        extractJavacPluginJsr199Fields(javaAnnotationProcessorParams, ruleCellRoot),
        extractJavacPluginJsr199Fields(standardJavacPluginParams, ruleCellRoot),
        !javaAnnotationProcessorParams.isEmpty());
  }

  /** Creates {@link ResolvedJavacOptions} */
  public static ResolvedJavacOptions of(
      Optional<String> bootclasspath,
      Optional<List<RelPath>> bootclasspathList,
      JavacLanguageLevelOptions languageLevelOptions,
      boolean debug,
      boolean verbose,
      JavacPluginParams javaAnnotationProcessorParams,
      JavacPluginParams standardJavacPluginParams,
      List<String> extraArguments,
      List<JavacPluginJsr199Fields> annotationProcessors,
      List<JavacPluginJsr199Fields> javaPlugins,
      boolean javaAnnotationProcessorParamsPresent) {
    return ImmutableResolvedJavacOptions.ofImpl(
        bootclasspath,
        bootclasspathList,
        languageLevelOptions,
        debug,
        verbose,
        javaAnnotationProcessorParams,
        standardJavacPluginParams,
        extraArguments,
        annotationProcessors,
        javaPlugins,
        javaAnnotationProcessorParamsPresent);
  }

  private static ImmutableList<JavacPluginJsr199Fields> extractJavacPluginJsr199Fields(
      JavacPluginParams javacPluginParams, AbsPath ruleCellRoot) {
    return javacPluginParams.getPluginProperties().stream()
        .map(p -> p.getJavacPluginJsr199Fields(ruleCellRoot))
        .collect(ImmutableList.toImmutableList());
  }

  /** Validates classpath */
  public void validateClasspath(Function<String, Boolean> classpathChecker) throws IOException {
    Optional<String> bootclasspath = getBootclasspath();
    if (!bootclasspath.isPresent()) {
      return;
    }
    String bootClasspath = bootclasspath.get();

    try {
      if (!classpathChecker.apply(bootClasspath)) {
        throw new IOException(
            String.format("Bootstrap classpath %s contains no valid entries", bootClasspath));
      }
    } catch (UncheckedIOException e) {
      throw e.getCause();
    }
  }
}
