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

package com.facebook.buck.test.selectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;

import org.junit.Test;

public class TestSelectionListTest {

  public static final TestDescription CAR_DOORS = new TestDescription(
      "com.example.clown.Car", "testDoors");
  public static final TestDescription SHOE_LACES = new TestDescription(
      "com.example.clown.Shoes", "testLaces");
  public static final TestDescription PM_DECREE = new TestDescription(
      "com.example.clown.PrimeMinisterialDecree", "formAllianceWithClowns");

  @Test
  public void shouldNotFilterTestsThatAreIncluded() {
    TestSelectorList selectomatic = new TestSelectorList.Builder()
        .addRawSelectors("com.example.clown.Car")
        .build();

    assertTrue(selectomatic.isIncluded(CAR_DOORS));
    assertFalse(selectomatic.defaultIsInclusive);
  }

  @Test
  public void shouldFilterTestsThatAreExcluded() {
    TestSelectorList selectomatic = new TestSelectorList.Builder()
        .addRawSelectors("!com.example.clown.Car")
        .build();

    assertFalse(selectomatic.isIncluded(CAR_DOORS));
    assertTrue(selectomatic.defaultIsInclusive);
  }

  @Test
  public void shouldIncludeThingsWeExplicitlyWantToInclude() {
    TestSelectorList selectotron = new TestSelectorList.Builder()
        .addRawSelectors("com.example.clown.Car")
        .addRawSelectors("com.example.clown.Shoes")
        .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS));
    assertTrue(selectotron.isIncluded(SHOE_LACES));
    assertFalse(selectotron.isIncluded(PM_DECREE));
    assertFalse(selectotron.defaultIsInclusive);
  }

  @Test
  public void testExcludeSomeTestsButIncludeByDefault() {
    TestSelectorList selectotron = new TestSelectorList.Builder()
        .addRawSelectors("!com.example.clown.PrimeMinisterialDecree")
        .addRawSelectors("!com.example.clown.Shoes")
        .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS));
    assertFalse(selectotron.isIncluded(SHOE_LACES));
    assertFalse(selectotron.isIncluded(PM_DECREE));
    assertTrue(selectotron.defaultIsInclusive);
  }

  @Test
  public void testExcludeMethodsWithEverythingElseIncluded() {
    TestSelectorList selectotron = new TestSelectorList.Builder()
        .addRawSelectors("!#testLaces")
        .addRawSelectors("!#formAllianceWithClowns")
        .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS));
    assertFalse(selectotron.isIncluded(SHOE_LACES));
    assertFalse(selectotron.isIncluded(PM_DECREE));
  }

  @Test
  public void shouldExplainItself() {
    TestSelectorList testSelectorList = new TestSelectorList.Builder()
        .addRawSelectors("!ClownTest")
        .addRawSelectors("ShoesTest#testLaces")
        .addRawSelectors("#testTalent")
        .build();

    assertFalse(testSelectorList.defaultIsInclusive);

    ImmutableList<String> expected = ImmutableList.of(
        "exclude class:ClownTest method:<any>",
        "include class:ShoesTest method:testLaces",
        "include class:<any> method:testTalent",
        "exclude everything else");

    assertEquals(expected, testSelectorList.getExplanation());
  }

  @Test
  public void shouldCollapseCatchAllFinalSelector() {
    TestSelectorList testSelectorList = new TestSelectorList.Builder()
        .addRawSelectors("!A", "B#c", "#d", "#")
        .build();

    ImmutableList<String> expected = ImmutableList.of(
        "exclude class:A method:<any>",
        "include class:B method:c",
        "include class:<any> method:d",
        "include everything else");

    assertEquals(expected, testSelectorList.getExplanation());
  }

}
