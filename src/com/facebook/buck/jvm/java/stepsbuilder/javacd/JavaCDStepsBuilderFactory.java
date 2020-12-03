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

import com.facebook.buck.jvm.java.stepsbuilder.AbiJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.AbiJarStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryJarPipelineStepsBuilder;
import com.facebook.buck.jvm.java.stepsbuilder.JavaLibraryJarStepsBuilder;

/**
 * Factory that creates {@link JavaCompileStepsBuilder } builders instances applicable to JavaCD.
 */
public class JavaCDStepsBuilderFactory implements JavaCompileStepsBuilderFactory {

  /** Creates an appropriate {@link JavaLibraryJarStepsBuilder} instance. */
  @Override
  public JavaLibraryJarStepsBuilder getLibraryJarBuilder() {
    throw new UnsupportedOperationException();
  }

  /** Creates an appropriate {@link JavaLibraryJarPipelineStepsBuilder} instance. */
  @Override
  public JavaLibraryJarPipelineStepsBuilder getPipelineLibraryJarBuilder() {
    throw new UnsupportedOperationException();
  }

  /** Creates an appropriate {@link AbiJarStepsBuilder} instance. */
  @Override
  public AbiJarStepsBuilder getAbiJarBuilder() {
    throw new UnsupportedOperationException();
  }

  /** Creates an appropriate {@link AbiJarPipelineStepsBuilder} instance. */
  @Override
  public AbiJarPipelineStepsBuilder getPipelineAbiJarBuilder() {
    throw new UnsupportedOperationException();
  }
}
