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

package com.facebook.buck.jvm.java.testutil.compiler;

import org.junit.runners.Parameterized;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.TestClass;

/**
 * Parameterized test runner that enables tests to work with the Compiler Tree API implementation
 * corresponding to the compiler returned by {@link
 * javax.tools.ToolProvider#getSystemJavaCompiler()}. These are public APIs that are not provided in
 * rt.jar and thus are not usually on the classpath.
 */
public class CompilerTreeApiParameterized extends Parameterized {
  public CompilerTreeApiParameterized(Class<?> klass) throws Throwable {
    super(klass);
  }

  @Override
  protected TestClass createTestClass(Class<?> testClass) {
    try {
      return new TestClass(CompilerTreeApiTestRunner.reloadFromCompilerClassLoader(testClass));
    } catch (InitializationError initializationError) {
      throw new AssertionError(initializationError);
    }
  }
}
