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

import com.facebook.buck.rules.TestRule;
import com.facebook.buck.step.Step;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Immutable value type holding data about the test(s) invoked by a {@link TestRule}.
 */
@Value.Immutable
@BuckStyleImmutable
interface AbstractTestRun {
  @Value.Parameter
  TestRule getTest();

  @Value.Parameter
  List<Step> getSteps();

  @Value.Parameter
  Callable<TestResults> getTestResultsCallable();

  @Value.Parameter
  TestRule.TestReportingCallback getTestReportingCallback();
}
