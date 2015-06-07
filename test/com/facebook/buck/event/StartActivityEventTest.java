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

public class StartActivityEventTest {
  @Test
  public void testEquals() throws Exception {
    StartActivityEvent started =
        configureTestEvent(StartActivityEvent.started(BuildTargetFactory.newInstance("//foo:bar"),
            "com.foo.bar"));
    StartActivityEvent startedTwo =
        configureTestEvent(StartActivityEvent.started(BuildTargetFactory.newInstance("//foo:bar"),
            "com.foo.bar"));
    StartActivityEvent finished =
        configureTestEvent(StartActivityEvent.finished(BuildTargetFactory.newInstance("//foo:bar"),
            "com.foo.bar",
            false));
    StartActivityEvent finishedTwo =
        configureTestEvent(StartActivityEvent.finished(BuildTargetFactory.newInstance("//foo:bar"),
            "com.foo.bar",
            false));
    StartActivityEvent finishedSucceed =
        configureTestEvent(StartActivityEvent.finished(BuildTargetFactory.newInstance("//foo:bar"),
            "com.foo.bar",
            true));

    assertEquals(started, started);
    assertNotEquals(started, finished);
    assertEquals(started, startedTwo);
    assertEquals(finished, finishedTwo);
    assertNotEquals(finished, finishedSucceed);
  }
}
