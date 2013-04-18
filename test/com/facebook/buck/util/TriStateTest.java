/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * It may seem silly to have such a thorough unit test for such a simple class, but on the contrary,
 * think about the insidious bugs that would be caused if the sense of one of the boolean methods
 * of {@link TriState} were flipped. When debugging, verifying the behavior of TriState is likely
 * the last thing that someone would check because the developer would assume that TriState would
 * be so simple that it could not possibly be the source of the problem.
 */
public class TriStateTest {

  @Test
  public void testSetCardinality() {
    assertEquals(3, TriState.values().length);
  }

  @Test
  public void testIsSet() {
    assertEquals(true, TriState.TRUE.isSet());
    assertEquals(true, TriState.FALSE.isSet());
    assertEquals(false, TriState.UNSPECIFIED.isSet());
  }

  @Test
  public void testAsBoolean() {
    assertEquals(true, TriState.TRUE.asBoolean());
    assertEquals(false, TriState.FALSE.asBoolean());
  }

  @Test(expected = IllegalStateException.class)
  public void testAsBooleanThrowsForUnspecified() {
    TriState.UNSPECIFIED.asBoolean();
  }

  @Test
  public void testForBooleanValue() {
    assertSame(TriState.TRUE, TriState.forBooleanValue(true));
    assertSame(TriState.FALSE, TriState.forBooleanValue(false));
  }
}
