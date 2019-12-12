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

package com.facebook.buck.util.timing;

import org.junit.Assert;
import org.junit.Test;

public class ClockDurationTest {

  @Test
  public void testGetters() {
    ClockDuration duration = new ClockDuration(11, 12, 13);
    Assert.assertEquals(11, duration.getWallMillisDuration());
    Assert.assertEquals(12, duration.getNanoDuration());
    Assert.assertEquals(13, duration.getThreadUserNanoDuration());

    Assert.assertEquals(0, ClockDuration.ZERO.getWallMillisDuration());
    Assert.assertEquals(0, ClockDuration.ZERO.getNanoDuration());
    Assert.assertEquals(0, ClockDuration.ZERO.getThreadUserNanoDuration());
  }

  @Test
  public void testEqualsAndHashCode() {
    ClockDuration duration = new ClockDuration(1, 2, 3);
    ClockDuration duration0 = new ClockDuration(1, 2, 3);
    ClockDuration duration1 = new ClockDuration(10, 2, 3);
    ClockDuration duration2 = new ClockDuration(1, 20, 3);
    ClockDuration duration3 = new ClockDuration(1, 2, 30);
    Assert.assertNotEquals(duration, 42);
    Assert.assertEquals(duration, duration);
    Assert.assertEquals(duration, duration0);
    Assert.assertNotEquals(duration, duration1);
    Assert.assertNotEquals(duration, duration2);
    Assert.assertNotEquals(duration, duration3);
    Assert.assertEquals(duration.hashCode(), duration0.hashCode());
    Assert.assertNotEquals(duration.hashCode(), duration1.hashCode());
    Assert.assertNotEquals(duration.hashCode(), duration2.hashCode());
    Assert.assertNotEquals(duration.hashCode(), duration3.hashCode());
  }
}
