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

package com.facebook.buck.util.concurrent;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class TimeSpanTest {

  @Test
  public void testObserverMethods() {
    TimeSpan duration = new TimeSpan(10, TimeUnit.DAYS);
    assertEquals(10, duration.getDuration());
    assertEquals(TimeUnit.DAYS, duration.getUnit());
  }

  @Test
  public void testGetTimeSpanInMillis() {
    TimeSpan duration = new TimeSpan(2, TimeUnit.MINUTES);
    assertEquals(2 * 60 * 1000L, duration.getTimeSpanInMillis());
  }
}
