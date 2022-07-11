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

package com.facebook.buck.jvm.cd;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;

/**
 * Factory that creates {@link CompileStepsBuilder } builders instances that returns steps that is
 * ready to be executed in the current process.
 */
public class DefaultCompileStepsBuilderFactory<T extends CompileToJarStepFactory.ExtraParams>
    implements CompileStepsBuilderFactory {

  private final CompileToJarStepFactory<T> configuredCompiler;

  public DefaultCompileStepsBuilderFactory(CompileToJarStepFactory<T> configuredCompiler) {
    this.configuredCompiler = configuredCompiler;
  }

  /** Creates an appropriate {@link LibraryStepsBuilder} instance */
  @Override
  public LibraryStepsBuilder getLibraryBuilder() {
    return new DefaultLibraryStepsBuilder<>(configuredCompiler);
  }

  /** Creates an appropriate {@link AbiStepsBuilder} instance */
  @Override
  public AbiStepsBuilder getAbiBuilder() {
    return new DefaultAbiStepsBuilder<>(configuredCompiler);
  }
}
