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

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit test for {@link VersionStringComparator}.
 */
public class VersionStringComparatorTest {

  @Test
  public void testIsValidVersionString() {
    assertTrue(VersionStringComparator.isValidVersionString("4"));
    assertTrue(VersionStringComparator.isValidVersionString("4.2"));
    assertTrue(VersionStringComparator.isValidVersionString("4.2.2"));
    assertTrue(VersionStringComparator.isValidVersionString("4_rc1"));
    assertTrue(VersionStringComparator.isValidVersionString("4.2_rc1"));
    assertTrue(VersionStringComparator.isValidVersionString("4.2.2_rc1"));
  }

  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testCompare() {
    VersionStringComparator comparator = new VersionStringComparator();
    assertEquals(0, comparator.compare("4", "4"));
    assertEquals(0, comparator.compare("4.2.2", "4.2.2"));

    assertEquals(-1, comparator.compare("4", "5"));
    assertEquals(1, comparator.compare("5", "4"));

    assertEquals(-1, comparator.compare("4.2.2", "4.2.2.2"));
    assertEquals(1, comparator.compare("4.2.2.2", "4.2.2"));

    assertEquals(-1, comparator.compare("4.2.3", "4.3.2"));
    assertEquals(1, comparator.compare("4.3.2", "4.2.3"));

    assertThat(comparator.compare("4_rc1", "4"), equalTo(-1));
    assertThat(comparator.compare("4", "4_rc1"), equalTo(1));
    assertThat(comparator.compare("4.2.2_rc1", "4.2.2_rc2"), equalTo(-1));
    assertThat(comparator.compare("4.2.2_rc2", "4.2.2"), equalTo(-1));
  }

  @Test(expected = RuntimeException.class)
  public void testCompareThrowsRuntimeExceptionForInvalidParam1() {
    VersionStringComparator comparator = new VersionStringComparator();
    comparator.compare("foo", "4.2.2");
  }

  @Test(expected = RuntimeException.class)
  public void testCompareThrowsRuntimeExceptionForInvalidParam2() {
    VersionStringComparator comparator = new VersionStringComparator();
    comparator.compare("4.2.2", "foo");
  }
}
