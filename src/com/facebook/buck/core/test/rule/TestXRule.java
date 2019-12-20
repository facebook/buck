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

package com.facebook.buck.core.test.rule;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.build.execution.context.ExecutionContext;
import com.facebook.buck.core.rules.attr.HasSupplementaryOutputs;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestRunningOptions;
import com.google.common.collect.ImmutableList;
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
  CoercedTestRunnerSpec getSpecs();

  /** tests are no longer ran by Buck, so test protocol rules should not have this. */
  @Override
  @Deprecated
  default ImmutableList<Step> runTests(
      ExecutionContext executionContext,
      TestRunningOptions options,
      BuildContext buildContext,
      TestReportingCallback testReportingCallback) {
    throw shouldNotBeCalled();
  }

  /** tests are no longer ran by Buck, and hence we don't need to interpret the test results */
  @Override
  @Deprecated
  default Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      SourcePathResolverAdapter pathResolver,
      boolean isUsingTestSelectors) {
    throw shouldNotBeCalled();
  }

  /**
   * tests are not ran by Buck. This attribute should be on the test specs for the test runner to
   * interpret
   */
  @Override
  @Deprecated
  default boolean runTestSeparately() {
    return false;
  }

  /**
   * tests are not ran by Buck. This attribute should be on the test specs for the test runner to
   * interpret
   */
  @Override
  @Deprecated
  default boolean supportsStreamingTests() {
    return false;
  }

  /**
   * This shouldn't be overriden as the default implementation is sufficient. Unfortunately it's not
   * trivial to convert this to an Abstract class and make this final because rules also extend a
   * variety of AbstractBuildRule classes and multiple inheritance is not available in Java.
   *
   * @return the test protocol specs.
   */
  @Override
  default ExternalTestSpec getExternalTestRunnerSpec(
      ExecutionContext executionContext,
      TestRunningOptions testRunningOptions,
      BuildContext buildContext) {
    return ImmutableExternalRunnerTestProtocol.of(
        getBuildTarget(), getSpecs(), buildContext.getSourcePathResolver());
  }

  /** since Buck doesn't actually run the test, pre test steps make no sense. */
  @Override
  @Deprecated
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
