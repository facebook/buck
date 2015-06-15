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

package com.facebook.buck.event;

import static com.facebook.buck.event.TestEventConfigerator.configureTestEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.model.BuildTargetFactory;

import org.junit.Test;

public class InstallEventTest {
  @Test
  public void testEquals() throws Exception {
    InstallEvent started = configureTestEvent(
        InstallEvent.started(BuildTargetFactory.newInstance("//foo:bar")));
    InstallEvent startedTwo = configureTestEvent(
        InstallEvent.started(BuildTargetFactory.newInstance("//foo:bar")));
    InstallEvent finished = configureTestEvent(
        InstallEvent.finished(BuildTargetFactory.newInstance("//foo:bar"), true));
    InstallEvent finishedDifferentEvent = configureTestEvent(
        InstallEvent.finished(BuildTargetFactory.newInstance("//foo:raz"), true));
    InstallEvent finishedFail = configureTestEvent(
        InstallEvent.finished(BuildTargetFactory.newInstance("//foo:bar"), false));

    assertEquals(started, startedTwo);
    assertNotEquals(finished, finishedDifferentEvent);
    assertNotEquals(started, finished);
    assertNotEquals(finished, finishedFail);

  }
}
