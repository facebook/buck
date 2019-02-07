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
package com.facebook.buck.test.config;

import com.facebook.buck.command.config.BuildBuckConfig;
import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.ConfigView;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.OptionalInt;
import org.immutables.value.Value;
import org.immutables.value.Value.Lazy;

@BuckStyleTuple
@Value.Immutable(builder = false, copy = false)
public abstract class AbstractTestBuckConfig implements ConfigView<BuckConfig> {

  private static final String TEST_SECTION_HEADER = "test";
  private static final String TEST_SUMMARY_SECTION_NAME = "test_summary";

  @Override
  public abstract BuckConfig getDelegate();

  @Lazy
  public boolean isParallelExternalTestSpecComputationEnabled() {
    return getDelegate()
        .getBooleanValue(
            TEST_SECTION_HEADER, "parallel_external_test_spec_computation_enabled", false);
  }

  @Lazy
  public long getDefaultTestTimeoutMillis() {
    return Long.parseLong(getDelegate().getValue(TEST_SECTION_HEADER, "timeout").orElse("0"));
  }

  /** Return Strings so as to avoid a dependency on {@link com.facebook.buck.cli.LabelSelector}! */
  @Lazy
  public ImmutableList<String> getDefaultRawExcludedLabelSelectors() {
    return getDelegate().getListWithoutComments(TEST_SECTION_HEADER, "excluded_labels");
  }

  /**
   * @return the number of threads Buck should use for testing. This will use the test.threads
   *     setting if it exists. Otherwise, this will use the build parallelization settings if not
   *     configured.
   */
  @Lazy
  public int getNumTestThreads() {
    OptionalInt numTestThreads = getDelegate().getInteger(TEST_SECTION_HEADER, "threads");
    if (numTestThreads.isPresent()) {
      int num = numTestThreads.getAsInt();
      if (num <= 0) {
        throw new HumanReadableException(
            "test.threads must be greater than zero (was " + num + ")");
      }
      return num;
    }
    double ratio =
        getDelegate().getFloat(TEST_SECTION_HEADER, "thread_utilization_ratio").orElse(1.0F);
    if (ratio <= 0.0F) {
      throw new HumanReadableException(
          "thread_utilization_ratio must be greater than zero (was " + ratio + ")");
    }
    return (int) Math.ceil(ratio * getDelegate().getView(BuildBuckConfig.class).getNumThreads());
  }

  public TestResultSummaryVerbosity getResultSummaryVerbosity() {
    boolean includeStdErr =
        getDelegate().getBooleanValue(TEST_SUMMARY_SECTION_NAME, "include_std_err", true);
    boolean includeStdOut =
        getDelegate().getBooleanValue(TEST_SUMMARY_SECTION_NAME, "include_std_out", true);

    return TestResultSummaryVerbosity.of(includeStdErr, includeStdOut);
  }

  public Optional<ImmutableList<String>> getExternalTestRunner() {
    Optional<String> value = getDelegate().getValue(TEST_SECTION_HEADER, "external_runner");
    return value.map(
        configValue -> ImmutableList.copyOf(Splitter.on(' ').splitToList(configValue)));
  }

  /** The timeout to apply to entire test rules. */
  public Optional<Long> getDefaultTestRuleTimeoutMs() {
    return getDelegate().getLong(TEST_SECTION_HEADER, "rule_timeout");
  }

  public boolean isInclNoLocationClassesEnabled() {
    return getDelegate().getBooleanValue(TEST_SECTION_HEADER, "incl_no_location_classes", false);
  }

  public Optional<ImmutableList<String>> getCoverageIncludes() {
    return getDelegate().getOptionalListWithoutComments("test", "coverageIncludes", ',');
  }

  public Optional<ImmutableList<String>> getCoverageExcludes() {
    return getDelegate().getOptionalListWithoutComments("test", "coverageExcludes", ',');
  }

  public boolean isBuildingFilteredTestsEnabled() {
    return getDelegate().getBooleanValue("test", "build_filtered_tests", false);
  }
}
