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

package com.facebook.buck.test.external;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.ExitCode;
import com.google.common.collect.ImmutableSet;
import org.junit.Test;

public class ExternalTestRunEventTest {

  @Test
  public void startAndStopShouldRelateProperlyBasedOnHash() {
    ImmutableSet<String> tests = ImmutableSet.of("//exmaple:other", "//thing/made/of:cheese");

    ExternalTestRunEvent.Started started =
        ExternalTestRunEvent.started(false, TestSelectorList.empty(), false, tests);
    ExternalTestRunEvent.Finished finished =
        ExternalTestRunEvent.finished(tests, /* exitCode */ ExitCode.SUCCESS);

    assertTrue(started.isRelatedTo(finished));
    assertTrue(finished.isRelatedTo(started));
  }

  @Test
  public void shouldNotBelieveThatEventsThatAreNotRelatedAreRelated() {
    ImmutableSet<String> tests = ImmutableSet.of("//exmaple:other", "//thing/made/of:cheese");
    ImmutableSet<String> otherTests = ImmutableSet.of("//example:test");

    ExternalTestRunEvent.Started started =
        ExternalTestRunEvent.started(false, TestSelectorList.empty(), false, tests);
    ExternalTestRunEvent.Finished finished =
        ExternalTestRunEvent.finished(otherTests, /* exitCode */ ExitCode.BUILD_ERROR);

    assertFalse(started.isRelatedTo(finished));
    assertFalse(finished.isRelatedTo(started));
  }
}
