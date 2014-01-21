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

import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class TestRunEventTest {

  @Test
  public void startAndStopShouldPairUpProperlyBasedOnHash() {
    ImmutableList<String> tests = ImmutableList.of("//exmaple:other", "//thing/made/of:cheese");

    TestRunEvent.Started started = TestRunEvent.started(
        false, Optional.<TestSelectorList>absent(), false, tests);
    TestRunEvent.Finished finished = TestRunEvent.finished(
        tests, ImmutableList.<TestResults>of());

    assertTrue(started.eventsArePair(finished));
    assertTrue(finished.eventsArePair(started));
  }

  @Test
  public void shouldNotBelieveThatEventsThatAreNotPairsArePairs() {
    ImmutableList<String> tests = ImmutableList.of("//exmaple:other", "//thing/made/of:cheese");
    ImmutableList<String> otherTests = ImmutableList.of("//example:test");

    TestRunEvent.Started started = TestRunEvent.started(
        false, Optional.<TestSelectorList>absent(), false, tests);
    TestRunEvent.Finished finished = TestRunEvent.finished(
        otherTests, ImmutableList.<TestResults>of());

    assertFalse(started.eventsArePair(finished));
    assertFalse(finished.eventsArePair(started));
  }

}
