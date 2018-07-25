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

import static com.facebook.buck.event.TestEventConfigurator.configureTestEvent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import com.facebook.buck.core.model.BuildTargetFactory;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.Test;

public class InstallEventTest {
  @Test
  public void testEquals() {
    InstallEvent.Started started =
        configureTestEvent(InstallEvent.started(BuildTargetFactory.newInstance("//foo:bar")));
    InstallEvent.Started startedTwo =
        configureTestEvent(InstallEvent.started(BuildTargetFactory.newInstance("//foo:bar")));
    InstallEvent.Started startedDifferentEvent =
        configureTestEvent(InstallEvent.started(BuildTargetFactory.newInstance("//foo:raz")));
    InstallEvent finished =
        configureTestEvent(
            InstallEvent.finished(started, true, Optional.empty(), Optional.empty()));
    InstallEvent finishedDifferentEvent =
        configureTestEvent(
            InstallEvent.finished(startedDifferentEvent, true, Optional.empty(), Optional.empty()));
    InstallEvent finishedFail =
        configureTestEvent(
            InstallEvent.finished(started, false, Optional.empty(), Optional.empty()));

    assertNotEquals(started, startedTwo);
    assertNotEquals(finished, finishedDifferentEvent);
    assertNotEquals(started, finished);
    try {
      finished.equals(finishedFail);
      fail("Expected an UnsupportedOperationException.");
    } catch (UnsupportedOperationException e) {
      assertThat(e.toString(), Matchers.stringContainsInOrder("conflicting", "events"));
    }

    assertThat(started.isRelatedTo(finished), Matchers.is(true));
    assertThat(started.isRelatedTo(startedTwo), Matchers.is(false));

    assertNotEquals(started.getEventKey(), startedTwo.getEventKey());
    assertEquals(started.getEventKey(), finished.getEventKey());
  }
}
