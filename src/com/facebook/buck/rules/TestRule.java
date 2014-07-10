/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * A {@link BuildRule} that is designed to run tests.
 */
public interface TestRule extends HasBuildTarget {

  /**
   * Returns a boolean indicating whether the files that contain the test results for this rule are
   * present.
   * <p>
   * If this method returns {@code true}, then
   * {@link #interpretTestResults(ExecutionContext, boolean, boolean)}
   * should be able to be called directly.
   */
  public boolean hasTestResultFiles(ExecutionContext executionContext);

  /**
   * Returns the commands required to run the tests.
   * <p>
   * <strong>Note:</strong> This method may be run without
   * {@link BuildEngine#build(BuildContext, BuildRule)} having been run. This happens if the user
   * has built [and ran] the test previously and then re-runs it using the {@code --debug} flag.
   *
   * @param buildContext Because this method may be run without
   *     {@link BuildEngine#build(BuildContext, BuildRule)} having been run, this is supplied in
   *     case any non-cacheable build work needs to be done.
   * @param isDryRun
   * @param executionContext Provides context for creating {@link Step}s.
   * @param testSelectorList Provides a way of selecting which tests to include or exclude
   *     from a run.
   * @return the commands required to run the tests
   */
  public ImmutableList<Step> runTests(
      BuildContext buildContext,
      ExecutionContext executionContext,
      boolean isDryRun,
      TestSelectorList testSelectorList);

  public Callable<TestResults> interpretTestResults(
      ExecutionContext executionContext,
      boolean isUsingTestSelectors,
      boolean isDryRun);

  /**
   * @return The set of labels for this build rule.
   */
  public ImmutableSet<Label> getLabels();

  /**
   * @return The set of email addresses to act as contact points for this test.
   */
  public ImmutableSet<String> getContacts();

  /**
   * @return The set of {@link BuildRule} instances that this test is testing.
   */
  public ImmutableSet<BuildRule> getSourceUnderTest();

  /**
   * @return The relative path to the output directory of the test rule.
   */
  public Path getPathToTestOutputDirectory();
}
