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

import com.facebook.buck.event.external.elements.TestResultsExternalInterface;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Represents the test results from multiple test cases. */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractTestResults implements TestResultsExternalInterface<TestCaseSummary> {

  @Value.Parameter
  public abstract BuildTarget getBuildTarget();

  @Value.Derived
  @Override
  public boolean isSuccess() {
    for (TestCaseSummary result : getTestCases()) {
      if (!result.isSuccess()) {
        return false;
      }
    }
    return true;
  }

  @Value.Derived
  @Override
  public boolean hasAssumptionViolations() {
    for (TestCaseSummary result : getTestCases()) {
      if (result.hasAssumptionViolations()) {
        return true;
      }
    }
    return false;
  }

  @Value.Derived
  public int getFailureCount() {
    int failureCount = 0;
    for (TestCaseSummary result : getTestCases()) {
      if (!result.isSuccess()) {
        failureCount += result.getFailureCount();
      }
    }
    return failureCount;
  }

  @JsonIgnore
  @Value.Derived
  public ImmutableList<TestCaseSummary> getFailures() {
    ImmutableList.Builder<TestCaseSummary> failures = ImmutableList.builder();
    for (TestCaseSummary result : getTestCases()) {
      if (!result.isSuccess()) {
        failures.add(result);
      }
    }
    return failures.build();
  }

  @Value.Parameter
  @Override
  public abstract ImmutableList<TestCaseSummary> getTestCases();

  @Value.Parameter
  public abstract ImmutableSet<String> getContacts();

  @Value.Parameter
  public abstract ImmutableSet<String> getLabels();

  @JsonIgnore
  public abstract ImmutableList<Path> getTestLogPaths();

  @Value.Default
  @Override
  public boolean getDependenciesPassTheirTests() {
    return true;
  }

  @Value.Default
  public int getSequenceNumber() {
    return 0;
  }

  @Value.Default
  @Override
  public int getTotalNumberOfTests() {
    return 0;
  }

  @Override
  public String toString() {
    return String.format("%s (success=%s) %s", super.toString(), isSuccess(), getTestCases());
  }
}
