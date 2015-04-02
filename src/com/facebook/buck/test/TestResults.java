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

package com.facebook.buck.test;

import com.facebook.buck.model.BuildTarget;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.annotations.Beta;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.util.List;

/**
 * Represents the test results from multiple test cases.
 */
public class TestResults {

  private static final BuildTarget DUMMY_TARGET_FOR_TESTING =
      BuildTarget.builder("//foo/bar", "baz").build();

  private final BuildTarget source;
  private final ImmutableList<TestCaseSummary> testCases;
  private final ImmutableList<TestCaseSummary> failures;
  private final int failureCount;
  private final ImmutableSet<String> contacts;
  private final ImmutableSet<String> labels;
  private final boolean hasAssumptionViolations;
  private boolean dependenciesPassTheirTests = true;

  @VisibleForTesting
  public TestResults(List<TestCaseSummary> testCases) {
    this(
        DUMMY_TARGET_FOR_TESTING,
        testCases,
        /* contacts */ ImmutableSet.<String>of(),
        /* labels */ ImmutableSet.<String>of());
  }

  @Beta
  public TestResults(
      BuildTarget source,
      List<TestCaseSummary> testCases,
      ImmutableSet<String> contacts,
      ImmutableSet<String> labels) {
    this.source = source;
    this.testCases = ImmutableList.copyOf(testCases);
    this.contacts = contacts;
    this.labels = labels;
    boolean hasAssumptionViolations = false;

    int failureCount = 0;
    ImmutableList.Builder<TestCaseSummary> failures = ImmutableList.builder();
    for (TestCaseSummary result : testCases) {
      if (result.hasAssumptionViolations()) {
        hasAssumptionViolations = true;
      }
      if (!result.isSuccess()) {
        failures.add(result);
        failureCount += result.getFailureCount();
      }
    }
    this.failures = failures.build();
    this.failureCount = failureCount;
    this.hasAssumptionViolations = hasAssumptionViolations;
  }

  @JsonIgnore
  public BuildTarget getBuildTarget() {
    return source;
  }

  public boolean isSuccess() {
    return failures.isEmpty();
  }

  public boolean hasAssumptionViolations() {
    return hasAssumptionViolations;
  }

  public int getFailureCount() {
    return failureCount;
  }

  @JsonIgnore
  public ImmutableList<TestCaseSummary> getFailures() {
    return failures;
  }

  public ImmutableList<TestCaseSummary> getTestCases() {
    return testCases;
  }

  public ImmutableSet<String> getContacts() {
    return contacts;
  }

  public ImmutableSet<String> getLabels() {
    return labels;
  }

  public void setDependenciesPassTheirTests(boolean dependenciesPassTheirTests) {
    this.dependenciesPassTheirTests = dependenciesPassTheirTests;
  }

  public boolean getDependenciesPassTheirTests() {
    return dependenciesPassTheirTests;
  }
}

