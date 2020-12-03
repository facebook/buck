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

package com.facebook.buck.jvm.java.stepsbuilder;

import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.stepsbuilder.impl.DefaultJavaCompileStepsBuilderFactory;

/** Factory that creates {@link JavaCompileStepsBuilder} instances */
public interface JavaCompileStepsBuilderFactory {

  /** Creates an appropriate {@link JavaLibraryJarStepsBuilder} instance */
  JavaLibraryJarStepsBuilder getLibraryJarBuilder();

  /** Creates an appropriate {@link JavaLibraryJarPipelineStepsBuilder} instance */
  JavaLibraryJarPipelineStepsBuilder getPipelineLibraryJarBuilder();

  /** Creates an appropriate {@link AbiJarStepsBuilder} instance */
  AbiJarStepsBuilder getAbiJarBuilder();

  /** Creates an appropriate {@link AbiJarPipelineStepsBuilder} instance */
  AbiJarPipelineStepsBuilder getPipelineAbiJarBuilder();

  /** Returns specific implementation of {@link JavaCompileStepsBuilderFactory}. */
  static <T extends CompileToJarStepFactory.ExtraParams> JavaCompileStepsBuilderFactory getFactory(
      CompileToJarStepFactory<T> configuredCompiler) {
    if (configuredCompiler.supportsCompilationDaemon()) {
      // TODO : msemko@ return JavaCDStepsBuilderFactory when it is ready.
      return new DefaultJavaCompileStepsBuilderFactory<>(configuredCompiler);
    }
    return new DefaultJavaCompileStepsBuilderFactory<>(configuredCompiler);
  }
}
