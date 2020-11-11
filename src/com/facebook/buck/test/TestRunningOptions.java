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

package com.facebook.buck.test;

import com.facebook.buck.android.device.TargetDevice;
import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.collect.ImmutableMap;
import java.util.EnumSet;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.immutables.value.Value;

@BuckStyleValueWithBuilder
public abstract class TestRunningOptions {

  @Value.Default
  public boolean isCodeCoverageEnabled() {
    return false;
  }

  @Value.Default
  public boolean isDebugEnabled() {
    return false;
  }

  @Value.Default
  public boolean isRunAllTests() {
    return false;
  }

  @Value.Default
  public TestSelectorList getTestSelectorList() {
    return TestSelectorList.empty();
  }

  @Value.Default
  public boolean shouldExplainTestSelectorList() {
    return false;
  }

  @Value.Default
  public boolean isShufflingTests() {
    return false;
  }

  @Value.Default
  public long getDefaultTestTimeoutMillis() {
    return 0L;
  }

  @Value.Default
  public boolean isInclNoLocationClassesEnabled() {
    return false;
  }

  @Value.Default
  public boolean isRunWithDownwardApi() {
    return false;
  }

  public abstract Optional<String> getPathToXmlTestOutput();

  public abstract Optional<String> getPathToJavaAgent();

  public abstract OptionalInt getJavaForTestsVersion();

  @Value.Default
  public Set<CoverageReportFormat> getCoverageReportFormats() {
    return EnumSet.of(CoverageReportFormat.HTML);
  }

  @Value.Default
  public String getCoverageReportTitle() {
    return "Code-Coverage Analysis";
  }

  public abstract ImmutableMap<String, String> getEnvironmentOverrides();

  public abstract Optional<String> getCoverageExcludes();

  public abstract Optional<String> getCoverageIncludes();

  public abstract Optional<String> getJavaTempDir();

  public abstract Optional<TargetDevice> getTargetDevice();

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder extends ImmutableTestRunningOptions.Builder {}
}
