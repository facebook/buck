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

package com.facebook.buck.cli;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class CommandEventTest {
  @Test
  public void testEquals() throws Exception {
    CommandEvent startedDaemon = CommandEvent.started("build", true);
    CommandEvent startedDaemonTwo = CommandEvent.started("build", true);
    CommandEvent startedNoDaemon = CommandEvent.started("build", false);
    CommandEvent finishedDaemon = CommandEvent.finished("build", true, 0);
    CommandEvent finishedDaemonFailed = CommandEvent.finished("build", true, 1);
    CommandEvent finishedDifferentName = CommandEvent.finished("test", true, 0);


    assertEquals(startedDaemon, startedDaemonTwo);
    assertNotEquals(startedDaemon, startedNoDaemon);
    assertNotEquals(finishedDaemon, startedDaemon);
    assertNotEquals(finishedDaemon, finishedDaemonFailed);
    assertNotEquals(finishedDaemon, finishedDifferentName);
  }
}
