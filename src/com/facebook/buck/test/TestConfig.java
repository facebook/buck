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

package com.facebook.buck.test;

import com.facebook.buck.cli.BuckConfig;

public class TestConfig {
  public static final String TEST_SUMMARY_SECTION_NAME = "test_summary";
  public static final boolean DEFAULT_SUMMARY_INCLUDE_STDERR = true;
  public static final boolean DEFAULT_SUMMARY_INCLUDE_STDOUT = true;
  private final BuckConfig delegate;

  public TestConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public TestResultSummaryVerbosity getResultSummaryVerbosity() {
    boolean includeStdErr = delegate.getBooleanValue(
        TEST_SUMMARY_SECTION_NAME,
        "include_std_err",
        DEFAULT_SUMMARY_INCLUDE_STDERR);
    boolean includeStdOut = delegate.getBooleanValue(
        TEST_SUMMARY_SECTION_NAME,
        "include_std_out",
        DEFAULT_SUMMARY_INCLUDE_STDOUT);

    return TestResultSummaryVerbosity.of(includeStdErr, includeStdOut);
  }
}
