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

package com.facebook.buck.jvm.java.stepsbuilder.javacd;

import com.facebook.buck.javacd.model.BuildJavaCommand;
import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.impl.DefaultJavaCompileStepsBuilderFactory;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * Factory that creates {@link JavaCompileStepsBuilder } builders instances applicable to JavaCD.
 */
public class JavaCDStepsBuilderFactory implements JavaCompileStepsBuilderFactory {

  private final boolean hasAnnotationProcessing;
  private final BuildJavaCommand.SpoolMode spoolMode;
  private final boolean withDownwardApi;
  // TODO msemko: remove delegate when all builders are ready.
  private final DefaultJavaCompileStepsBuilderFactory<?> delegate;
  private final boolean isJavaCDEnabled;
  private final ImmutableList<String> javaRuntimeLauncherCommand;
  private final Supplier<Path> javacdBinaryPathSupplier;

  public JavaCDStepsBuilderFactory(
      BaseJavacToJarStepFactory configuredCompiler,
      DefaultJavaCompileStepsBuilderFactory<?> delegate,
      boolean isJavaCDEnabled,
      ImmutableList<String> javaRuntimeLauncherCommand,
      Supplier<Path> javacdBinaryPathSupplier) {
    this.hasAnnotationProcessing = configuredCompiler.hasAnnotationProcessing();
    this.spoolMode = configuredCompiler.getSpoolMode();
    this.withDownwardApi = configuredCompiler.isWithDownwardApi();
    this.delegate = delegate;
    this.isJavaCDEnabled = isJavaCDEnabled;
    this.javaRuntimeLauncherCommand = javaRuntimeLauncherCommand;
    this.javacdBinaryPathSupplier = javacdBinaryPathSupplier;
  }

  /** Creates an appropriate {@link LibraryJarStepsBuilder} instance. */
  @Override
  public LibraryJarStepsBuilder getLibraryJarBuilder() {
    return new JavaCDLibraryJarStepsBuilder(
        hasAnnotationProcessing,
        spoolMode,
        withDownwardApi,
        isJavaCDEnabled,
        javaRuntimeLauncherCommand,
        javacdBinaryPathSupplier);
  }

  /** Creates an appropriate {@link LibraryJarPipelineStepsBuilder} instance. */
  @Override
  public LibraryJarPipelineStepsBuilder getPipelineLibraryJarBuilder() {
    return delegate.getPipelineLibraryJarBuilder();
  }

  /** Creates an appropriate {@link AbiJarStepsBuilder} instance. */
  @Override
  public AbiJarStepsBuilder getAbiJarBuilder() {
    return new JavaCDAbiJarStepsBuilder(
        hasAnnotationProcessing,
        spoolMode,
        withDownwardApi,
        isJavaCDEnabled,
        javaRuntimeLauncherCommand,
        javacdBinaryPathSupplier);
  }

  /** Creates an appropriate {@link AbiJarPipelineStepsBuilder} instance. */
  @Override
  public AbiJarPipelineStepsBuilder getPipelineAbiJarBuilder() {
    return delegate.getPipelineAbiJarBuilder();
  }
}
