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

package com.facebook.buck.event;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.google.common.collect.ImmutableMap;

import org.junit.Test;


public class CompilerPluginDurationEventTest {


  @Test
  public void testEquals() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");
    String pluginName = "com.facebook.FakePlugin";
    String durationName = "fakeDuration";

    CompilerPluginDurationEvent startedEventOne = configureTestEvent(
        CompilerPluginDurationEvent.started(
            target,
            pluginName,
            durationName,
            ImmutableMap.<String, String>of()));
    CompilerPluginDurationEvent startedEventTwo = configureTestEvent(
        CompilerPluginDurationEvent.started(
            target,
            pluginName,
            durationName,
            ImmutableMap.<String, String>of()));

    assertEquals(startedEventOne, startedEventOne);
    assertNotEquals(startedEventOne, startedEventTwo);
  }

  @Test
  public void testIsRelated() throws Exception {
    BuildTarget target = BuildTargetFactory.newInstance("//fake:rule");
    String pluginName = "com.facebook.FakePlugin";
    String durationName = "fakeDuration";

    CompilerPluginDurationEvent.Started startedEventOne = configureTestEvent(
        CompilerPluginDurationEvent.started(
            target,
            pluginName,
            durationName,
            ImmutableMap.<String, String>of()));
    CompilerPluginDurationEvent.Started startedEventTwo = configureTestEvent(
        CompilerPluginDurationEvent.started(
            target,
            pluginName,
            durationName,
            ImmutableMap.<String, String>of()));
    CompilerPluginDurationEvent finishedEventOne = configureTestEvent(
        CompilerPluginDurationEvent.finished(
            startedEventOne,
            ImmutableMap.<String, String>of()));

    assertTrue(startedEventOne.isRelatedTo(finishedEventOne));
    assertTrue(finishedEventOne.isRelatedTo(startedEventOne));
    assertFalse(startedEventTwo.isRelatedTo(finishedEventOne));
    assertFalse(finishedEventOne.isRelatedTo(startedEventTwo));
  }
}
