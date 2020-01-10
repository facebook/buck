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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.JavaLibrary;

/**
 * The new Java Test runner rule for the test protocol.
 *
 * <p>This is used to specify the target that represents the test runner itself, which is a binary
 * that will have the test source compiled into it.
 */
public class JavaTestRunner extends NoopBuildRuleWithDeclaredAndExtraDeps {

  private final JavaLibrary compiledTestsLibrary;
  private final String mainClass;

  public JavaTestRunner(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      JavaLibrary compiledTestsLibrary,
      String mainClass) {
    super(buildTarget, projectFilesystem, params.copyAppendingExtraDeps(compiledTestsLibrary));
    this.compiledTestsLibrary = compiledTestsLibrary;
    this.mainClass = mainClass;
  }

  /** @return the underlying library that represents the runner */
  public JavaLibrary getCompiledTestsLibrary() {
    return compiledTestsLibrary;
  }

  /** @return the name of the main class for running */
  public String getMainClass() {
    return mainClass;
  }
}
