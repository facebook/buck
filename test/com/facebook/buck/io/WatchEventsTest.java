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

package com.facebook.buck.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.WatchEventsForTests;
import com.facebook.buck.testutil.integration.TemporaryPaths;

import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;

public class WatchEventsTest {

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Test
  public void testCreateContextStringForModifyEvent() throws IOException {
    Path file = tmp.newFile("foo.txt");
    WatchEvent<Path> modifyEvent = WatchEventsForTests.createPathEvent(
        file,
        StandardWatchEventKinds.ENTRY_MODIFY);
    assertEquals(
        file.toAbsolutePath().toString(),
        WatchEvents.createContextString(modifyEvent));
  }

  @Test
  public void testCreateContextStringForOverflowEvent() {
    WatchEvent<Object> overflowEvent = new WatchEvent<Object>() {
      @Override
      public Kind<Object> kind() {
        return StandardWatchEventKinds.OVERFLOW;
      }

      @Override
      public int count() {
        return 0;
      }

      @Override
      public Object context() {
        return new Object() {
          @Override
          public String toString() {
            return "I am the context string.";
          }
        };
      }
    };
    assertEquals("I am the context string.", WatchEvents.createContextString(overflowEvent));
  }

  @Test
  public void whenContextNullThenCreateContextStringReturnsValidString() {
    assertThat(
        "Context string should contain null.",
        WatchEvents.createContextString(WatchEventsForTests.createOverflowEvent()),
        Matchers.containsString("null"));
  }
}
