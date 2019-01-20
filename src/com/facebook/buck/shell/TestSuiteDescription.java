/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.model.targetgraph.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.test.rule.TestRule;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.immutables.value.Value;

/**
 * Produces rules used to logically group tests, allowing `buck test //:some_test_suite` behavior
 * that invokes all tests that this suite depends on. Also validates that created rules only depend
 * on Test rules and TestSuite rules.
 */
public class TestSuiteDescription implements DescriptionWithTargetGraph<TestSuiteDescriptionArg> {

  @Override
  public Class<TestSuiteDescriptionArg> getConstructorArgType() {
    return TestSuiteDescriptionArg.class;
  }

  @Override
  public TestSuite createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      TestSuiteDescriptionArg args) {

    validateTestDepsAreTestRules(buildTarget, params);

    return new TestSuite(buildTarget, context.getProjectFilesystem(), params);
  }

  private void validateTestDepsAreTestRules(BuildTarget buildTarget, BuildRuleParams params) {
    // "tests" are added to build deps via reflection
    ImmutableList<String> invalidTargets =
        params
            .getBuildDeps()
            .stream()
            .filter(r -> !(r instanceof TestRule || r instanceof TestSuite))
            .limit(5) // Too much more and it gets hard to read....
            .map(r -> r.getBuildTarget().getFullyQualifiedName())
            .collect(ImmutableList.toImmutableList());
    if (invalidTargets.size() > 0) {
      throw new HumanReadableException(
          "Non-test rule%s provided in `tests` in test_suite %s: %s",
          invalidTargets.size() > 1 ? "s" : "",
          buildTarget.getFullyQualifiedName(),
          Joiner.on("\n").join(invalidTargets));
    }
  }

  /** Args for test_suite */
  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractTestSuiteDescriptionArg extends CommonDescriptionArg {
    /** Test or TestSuite targets that should be invoked when this rule run through buck test */
    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getTests();
  }
}
