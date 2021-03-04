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

package com.facebook.buck.jvm.java.stepsbuilder.creator;

import com.facebook.buck.jvm.java.BaseJavacToJarStepFactory;
import com.facebook.buck.jvm.java.CompileToJarStepFactory;
import com.facebook.buck.jvm.java.stepsbuilder.JavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.impl.DefaultJavaCompileStepsBuilderFactory;
import com.facebook.buck.jvm.java.stepsbuilder.javacd.JavaCDStepsBuilderFactory;

/** Creator that creates an appropriate {@link JavaCompileStepsBuilderFactory}. */
public class JavaCompileStepsBuilderFactoryCreator {

  private JavaCompileStepsBuilderFactoryCreator() {}

  /** Returns specific implementation of {@link JavaCompileStepsBuilderFactory}. */
  public static <T extends CompileToJarStepFactory.ExtraParams>
      JavaCompileStepsBuilderFactory createFactory(
          CompileToJarStepFactory<T> configuredCompiler, JavaCDParams javaCDParams) {

    DefaultJavaCompileStepsBuilderFactory<T> defaultJavaCompileStepsBuilderFactory =
        new DefaultJavaCompileStepsBuilderFactory<>(configuredCompiler);

    if (configuredCompiler.supportsCompilationDaemon()) {
      BaseJavacToJarStepFactory baseJavacToJarStepFactory =
          (BaseJavacToJarStepFactory) configuredCompiler;
      return new JavaCDStepsBuilderFactory(
          baseJavacToJarStepFactory, defaultJavaCompileStepsBuilderFactory, javaCDParams);
    }

    return defaultJavaCompileStepsBuilderFactory;
  }
}
