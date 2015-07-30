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
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit test for {@link VersionStringComparator}.
 */
public class VersionStringComparatorTest {

  @Test
  public void testIsValidVersionString() {
    String[] validVersions = {
        "4",
        "4.2",
        "4.2.2",
        "4_rc1",
        "4.2_rc1",
        "4.2.2_rc1",
        "r9c",
        "r10e-rc4"
    };

    for (String validVersion : validVersions) {
      assertTrue(validVersion, VersionStringComparator.isValidVersionString(validVersion));
    }
  }

  @Test
  @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
  public void testCompare() {

    // array of pairs of lower/higher strings
    String[][] testPairs = {
        {"4", "4.2.2"},
        {"4", "5"},
        {"4.2.2", "4.2.2.2"},
        {"4.2.3", "4.3.2"},
        {"4", "4_rc1"},
        {"4.2.2_rc1", "4.2.2_rc2"},
        {"4.2.2_rc1", "4.2.2_rc2-preview"},
        // Android NDK versions
        {"r9c", "r10e-rc4"},
        {"r10e", "r10e-rc4"},
        {"r10e-rc3", "r10e-rc4"},
        {"r10ab-rc3", "r10ae-rc4"}
    };

    VersionStringComparator comparator = new VersionStringComparator();

    for (String[] testPair : testPairs) {
      String lower = testPair[0];
      String higher = testPair[1];

      assertThat(
          lower + " not equal to self",
          comparator.compare(lower, lower), equalTo(0)
      );
      assertThat(
          higher + " not equal to self",
          comparator.compare(higher, higher), equalTo(0)
      );
      assertThat(
          lower + " not less than " + higher,
          comparator.compare(lower, higher), lessThan(0)
      );
      assertThat(
          higher + " not higher than " + lower,
          comparator.compare(higher, lower), greaterThan(0)
      );

    }
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
