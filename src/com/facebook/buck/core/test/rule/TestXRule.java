/*
 * Copyright 2019-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/** Marks a rule as implementing the test protocol */
public interface TestXRule extends TestRule, ExternalTestRunnerRule, HasSupplementaryOutputs {

  /**
   * The named output for test rules that maps to the location of where Buck materializes the final
   * test binary.
   */
  String TEST_BINARY_OUTPUT = "testbin";

  default IllegalStateException shouldNotBeCalled() {
    return new IllegalStateException("Should not be called for rules implementing test protocol");
  }

  /** @return the specs from the test protocol defined in BUCK files */
  ImmutableMap<String, String> getSpecs();

  @Override
  default ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    throw shouldNotBeCalled();
  }

  @Override
  default Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolver pathResolver,
      boolean isUsingTestSelectors) {
    throw shouldNotBeCalled();
  }

  @Override
  default boolean runTestSeparately() {
    return false;
  }

  @Override
  default boolean supportsStreamingTests() {
    return false;
  }

  @Override
  default ExternalTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return new ImmutableExternalRunnerTestProtocol(getBuildTarget(), getSpecs());
  }

  @Override
  default void onPreTest(BuildContext buildContext) {}

  @Nullable
  @Override
  default SourcePath getSourcePathToSupplementaryOutput(String name) {
    if (name.equals(TEST_BINARY_OUTPUT)) {
      return getSourcePathToOutput();
    }
    return null;
  }
}
