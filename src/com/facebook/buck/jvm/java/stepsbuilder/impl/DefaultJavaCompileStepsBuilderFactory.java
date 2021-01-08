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

package com.facebook.buck.jvm.java.stepsbuilder.impl;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.LibraryJarStepsBuilder;

/**
 * Factory that creates {@link JavaCompileStepsBuilder } builders instances that returns steps that
 * is ready to be executed in the current process.
 */
public class DefaultJavaCompileStepsBuilderFactory<T extends CompileToJarStepFactory.ExtraParams>
    implements JavaCompileStepsBuilderFactory {

  private final CompileToJarStepFactory<T> configuredCompiler;

  public DefaultJavaCompileStepsBuilderFactory(CompileToJarStepFactory<T> configuredCompiler) {
    this.configuredCompiler = configuredCompiler;
  }

  /** Creates an appropriate {@link LibraryJarStepsBuilder} instance */
  @Override
  public LibraryJarStepsBuilder getLibraryJarBuilder() {
    return new DefaultLibraryJarStepsBuilder<>(configuredCompiler);
  }

  /** Creates an appropriate {@link LibraryJarPipelineStepsBuilder} instance */
  @Override
  public LibraryJarPipelineStepsBuilder getPipelineLibraryJarBuilder() {
    return new DefaultLibraryJarPipelineStepsBuilder<>(configuredCompiler);
  }

  /** Creates an appropriate {@link AbiJarStepsBuilder} instance */
  @Override
  public AbiJarStepsBuilder getAbiJarBuilder() {
    return new DefaultAbiJarCompileStepsBuilder<>(configuredCompiler);
  }

  /** Creates an appropriate {@link AbiJarPipelineStepsBuilder} instance */
  @Override
  public AbiJarPipelineStepsBuilder getPipelineAbiJarBuilder() {
    return new DefaultAbiJarPipelineStepsBuilder<>(configuredCompiler);
  }
}
