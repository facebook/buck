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

import org.junit.Test;

import java.util.logging.Level;


public class ConsoleEventTest {

  @Test
  public void testEquals() {
    ConsoleEvent event1 = configureTestEvent(ConsoleEvent.create(Level.INFO, "HELLO"));
    ConsoleEvent event2 = configureTestEvent(ConsoleEvent.info("HELLO"));
    ConsoleEvent event3 = configureTestEvent(ConsoleEvent.create(Level.SEVERE, "HELLO"));
    ConsoleEvent event4 = configureTestEvent(ConsoleEvent.severe("FOO"));

    assertEquals(event1, event2);
    assertNotEquals(event1, event3);
    assertNotEquals(event3, event4);
  }
}
