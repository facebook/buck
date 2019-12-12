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

package com.facebook.buck.jvm.java.abi.source;

import com.facebook.buck.jvm.java.testutil.compiler.CompilerTreeApiTest;
import java.io.IOException;
import java.util.Map;
import org.junit.runners.Parameterized;

/**
 * A base class for tests that compare the behavior of javac's implementation of Elements and
 * TypeMirrors to Buck's Tree-backed one.
 */
public abstract class CompilerTreeApiParameterizedTest extends CompilerTreeApiTest {
  private static final String JAVAC = "javac";
  private static final String TREES = "trees";

  @Parameterized.Parameter public String implementation;

  @Parameterized.Parameters(name = "{0}")
  public static Object[] getParameters() {
    return new Object[] {JAVAC, TREES};
  }

  @Override
  protected boolean useFrontendOnlyJavacTask() {
    return testingTrees();
  }

  protected boolean testingJavac() {
    return implementation == JAVAC;
  }

  protected boolean testingTrees() {
    return implementation == TREES;
  }

  protected void withClasspathForJavacOnly(Map<String, String> fileNamesToContents)
      throws IOException {
    if (testingTrees()) {
      return;
    }
    super.withClasspath(fileNamesToContents);
  }
}
