/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Test;

public class SwiftVersionsTest {
  @Test
  public void testLessThanFourTwo() {
    String fourTwoDigit = SwiftCompile.validVersionString("4.1");
    assertThat(fourTwoDigit, equalTo("4"));

    String fourOneDigit = SwiftCompile.validVersionString("4");
    assertThat(fourOneDigit, equalTo("4"));

    String threeTwoDigitVersion1 = SwiftCompile.validVersionString("3.1");
    assertThat(threeTwoDigitVersion1, equalTo("3"));

    String threeTwoDigitVersion2 = SwiftCompile.validVersionString("3.5");
    assertThat(threeTwoDigitVersion2, equalTo("3"));

    String threeOneDigit = SwiftCompile.validVersionString("3");
    assertThat(threeOneDigit, equalTo("3"));
  }

  @Test
  public void testFourTwo() {
    String fourTwo = SwiftCompile.validVersionString("4.2");
    assertThat(fourTwo, equalTo("4.2"));
  }

  @Test
  public void testGreaterThanFourTwo() {
    String fourThree = SwiftCompile.validVersionString("4.3");
    assertThat(fourThree, equalTo("4.3"));

    String five = SwiftCompile.validVersionString("5");
    assertThat(five, equalTo("5"));

    String fiveOne = SwiftCompile.validVersionString("5.1");
    assertThat(fiveOne, equalTo("5.1"));

    String fiveNine = SwiftCompile.validVersionString("5.9");
    assertThat(fiveNine, equalTo("5.9"));
  }

  @Test
  public void testThreeDigits() {
    String threeZeroZero = SwiftCompile.validVersionString("3.0.0");
    assertThat(threeZeroZero, equalTo("3"));

    String fourTwoOne = SwiftCompile.validVersionString("4.2.1");
    assertThat(fourTwoOne, equalTo("4.2"));

    String fiveZeroZero = SwiftCompile.validVersionString("5.0.0");
    assertThat(fiveZeroZero, equalTo("5.0"));
  }
}
