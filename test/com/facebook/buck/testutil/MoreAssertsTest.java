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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class MoreAssertsTest {
  @Rule public ExpectedException exception = ExpectedException.none();

  @Test
  public void testIterablesEqualsFailure1() {
    exception.expect(AssertionError.class);
    exception.expectMessage(
        is(" Extraneous item 0 in the observed list (expected:[] observed:[1]): 1."));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), ImmutableList.of(1));
  }

  @Test
  public void testIterablesEqualsFailure2() {
    exception.expect(AssertionError.class);
    exception.expectMessage(
        is(
            " Item 0 in the lists should match (expected:[1, 2] observed:[2, 1]). expected:<1> but was:<2>"));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1, 2), ImmutableList.of(2, 1));
  }

  @Test
  public void testIterablesEqualsFailure3() {
    exception.expect(AssertionError.class);
    exception.expectMessage(
        is(" Item 0 does not exist in the observed list (expected:[1] observed:[]): 1"));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1), ImmutableList.of());
  }

  @Test
  public void testIterablesEqualsFailure4() {
    exception.expect(AssertionError.class);
    exception.expectMessage(is("expected:<[1]> but was:<null>"));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1), null);
  }

  @Test
  public void testIterablesEqualsFailure5() {
    exception.expect(AssertionError.class);
    exception.expectMessage(containsString("(expected:[%d %s] observed:[]): %d %s"));
    MoreAsserts.assertIterablesEquals(ImmutableList.of("%d %s"), ImmutableList.of());
  }

  @Test
  public void testIterablesEqualsFailure6() {
    exception.expect(AssertionError.class);
    exception.expectMessage(containsString("expected:[] observed:[%s %d]"));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), ImmutableList.of("%s %d"));
  }

  @Test
  public void testIterablesEqualsFailure7() {
    exception.expect(AssertionError.class);
    exception.expectMessage(containsString("expected:[%s] observed:[%s %d]"));
    MoreAsserts.assertIterablesEquals(ImmutableList.of("%s"), ImmutableList.of("%s %d"));
  }

  @Test
  public void testIterablesEqualsSuccess() {
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1), ImmutableList.of(1));
    MoreAsserts.assertIterablesEquals(ImmutableList.of(), ImmutableList.of());
    MoreAsserts.assertIterablesEquals(null, null);
    MoreAsserts.assertIterablesEquals(ImmutableList.of(1, 2, 3), ImmutableSortedSet.of(3, 2, 1));
    MoreAsserts.assertIterablesEquals(ImmutableList.of("%d %s"), ImmutableList.of("%d %s"));
  }
}
