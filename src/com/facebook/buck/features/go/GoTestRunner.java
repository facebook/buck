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

package com.facebook.buck.features.go;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * Specifies the test runner for go_test for test protocol. The test runner points to a go file that
 * is ran to generate the test main for the given set of tests.
 */
public class GoTestRunner extends NoopBuildRule {

  private final SourcePath testRunnerGenerator;

  public GoTestRunner(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePath testRunnerGenerator) {
    super(buildTarget, projectFilesystem);
    this.testRunnerGenerator = testRunnerGenerator;
  }

  public SourcePath getTestRunnerGenerator() {
    return testRunnerGenerator;
  }
}
