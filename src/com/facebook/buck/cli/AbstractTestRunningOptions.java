/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.test.CoverageReportFormat;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import com.google.common.base.Optional;

import org.immutables.value.Value;

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractTestRunningOptions {

  @Value.Default
  public boolean isUsingOneTimeOutputDirectories() {
    return false;
  }

  @Value.Default
  public boolean isCodeCoverageEnabled() {
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
  public boolean isIgnoreFailingDependencies() {
    return false;
  }

  @Value.Default
  public boolean isResultsCacheEnabled() {
    return true;
  }

  @Value.Default
  public boolean isDryRun() {
    return false;
  }

  @Value.Default
  public boolean isShufflingTests() {
    return false;
  }

  public abstract Optional<String> getPathToXmlTestOutput();

  @Value.Default
  public CoverageReportFormat getCoverageReportFormat() {
    return CoverageReportFormat.HTML;
  }
}
