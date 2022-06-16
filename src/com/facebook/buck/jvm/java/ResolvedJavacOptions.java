/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Resolved JavacOptions used in {@link JavacPipelineState} */
@BuckStyleValueWithBuilder
public abstract class ResolvedJavacOptions {

  public abstract Optional<String> getBootclasspath();

  public abstract ImmutableList<RelPath> getBootclasspathList();

  public abstract JavacLanguageLevelOptions getLanguageLevelOptions();

  public abstract boolean isDebug();

  public abstract boolean isVerbose();

  public abstract JavacPluginParams getJavaAnnotationProcessorParams();

  public abstract JavacPluginParams getStandardJavacPluginParams();

  public abstract List<String> getExtraArguments();

  public boolean isJavaAnnotationProcessorParamsPresent() {
    return !getJavaAnnotationProcessorParams().isEmpty();
  }

  /**
   * Like {@link JavacOptions#withJavaAnnotationProcessorParams(JavacPluginParams)}, but for after
   * the options have been resolved.
   */
  public ResolvedJavacOptions withJavaAnnotationProcessorParams(
      JavacPluginParams javaAnnotationProcessorParams) {
    if (getJavaAnnotationProcessorParams().equals(javaAnnotationProcessorParams)) {
      return this;
    }
    return ImmutableResolvedJavacOptions.builder()
        .from(this)
        .setJavaAnnotationProcessorParams(javaAnnotationProcessorParams)
        .build();
  }

  /** Creates {@link ResolvedJavacOptions} */
  public static ResolvedJavacOptions of(
      JavacOptions javacOptions, SourcePathResolverAdapter resolver, AbsPath ruleCellRoot) {

    JavacLanguageLevelOptions languageLevelOptions = javacOptions.getLanguageLevelOptions();
    ImmutableList<PathSourcePath> bootclasspath =
        javacOptions.getSourceToBootclasspath().get(languageLevelOptions.getSourceLevelValue());
    ImmutableList<RelPath> bootclasspathList;
    if (bootclasspath != null) {
      bootclasspathList =
          bootclasspath.stream()
              .map(resolver::getAbsolutePath)
              .map(ruleCellRoot::relativize)
              .collect(ImmutableList.toImmutableList());
    } else {
      bootclasspathList = ImmutableList.of();
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
        javacOptions.getExtraArguments());
  }

  /** Creates {@link ResolvedJavacOptions} */
  public static ResolvedJavacOptions of(
      Optional<String> bootclasspath,
      ImmutableList<RelPath> bootclasspathList,
      JavacLanguageLevelOptions languageLevelOptions,
      boolean debug,
      boolean verbose,
      JavacPluginParams javaAnnotationProcessorParams,
      JavacPluginParams standardJavacPluginParams,
      List<String> extraArguments) {
    return ImmutableResolvedJavacOptions.builder()
        .setBootclasspath(bootclasspath)
        .setBootclasspathList(bootclasspathList)
        .setLanguageLevelOptions(languageLevelOptions)
        .setDebug(debug)
        .setVerbose(verbose)
        .setJavaAnnotationProcessorParams(javaAnnotationProcessorParams)
        .setStandardJavacPluginParams(standardJavacPluginParams)
        .setExtraArguments(extraArguments)
        .build();
  }

  /** Validates classpath */
  public void validateClasspath(Function<String, Boolean> classpathChecker) throws IOException {
    Optional<String> bootclasspath = getBootclasspath();
    if (bootclasspath.isEmpty()) {
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
