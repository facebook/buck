/*
 * Copyright 2014-present Facebook, Inc.
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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Throwables;

import org.junit.Test;

import java.nio.file.AccessDeniedException;

public class ThrowableConsoleEventTest {

  @Test
  public void throwableClassNameIsIncludedInDescription() {
    ThrowableConsoleEvent event = new ThrowableConsoleEvent(
        new AccessDeniedException("somefile"),
        "message");
    assertTrue(event.getMessage().contains("AccessDeniedException"));
  }

  @Test
  public void throwableStackTraceIsIncludedInDescription() {
    Throwable throwable = new AccessDeniedException("somefile");
    ThrowableConsoleEvent event = new ThrowableConsoleEvent(throwable, "message");
    assertThat(event.getMessage(), containsString(Throwables.getStackTraceAsString(throwable)));
  }
}
