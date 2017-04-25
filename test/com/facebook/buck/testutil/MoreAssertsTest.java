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

package com.facebook.buck.testutil;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Test;

public class MoreAssertsTest {

  @Test(expected = AssertionError.class)
  public void testIterablesEqualsFailure1() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), ImmutableList.of(1));
  }

  @Test(expected = AssertionError.class)
  public void testIterablesEqualsFailure2() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1, 2), ImmutableList.of(2, 1));
  }

  @Test(expected = AssertionError.class)
  public void testIterablesEqualsFailure3() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1), ImmutableList.of());
  }

  @Test(expected = AssertionError.class)
  public void testIterablesEqualsFailure4() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1), null);
  }

  @Test
  public void testIterablesEqualsSuccess() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1), ImmutableList.of(1));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), ImmutableList.of());
    MoreAsserts.assertIterablesEquals(null, null);
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1, 2, 3), ImmutableSortedSet.of(3, 2, 1));
  }
}
