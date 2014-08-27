/*
 * Copyright 2013-present Facebook, Inc.
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResults;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class IndividualTestEventTest {
  @Test
  public void startAndStopShouldRelateProperlyBasedOnHash() {
    ImmutableList<String> tests = ImmutableList.of("//exmaple:other", "//thing/made/of:cheese");

    IndividualTestEvent.Started started = IndividualTestEvent.started(tests);
    IndividualTestEvent.Finished finished = IndividualTestEvent.finished(
        tests, new TestResults(ImmutableList.<TestCaseSummary>of()));

    assertTrue(started.isRelatedTo(finished));
    assertTrue(finished.isRelatedTo(started));
  }

  @Test
  public void shouldNotBelieveThatEventsThatAreNotRelatedAreRelated() {
    ImmutableList<String> tests = ImmutableList.of("//exmaple:other", "//thing/made/of:cheese");
    ImmutableList<String> otherTests = ImmutableList.of("//example:test");

    IndividualTestEvent.Started started = IndividualTestEvent.started(tests);
    IndividualTestEvent.Finished finished = IndividualTestEvent.finished(
        otherTests, new TestResults(ImmutableList.<TestCaseSummary>of()));

    assertFalse(started.isRelatedTo(finished));
    assertFalse(finished.isRelatedTo(started));
  }
}
