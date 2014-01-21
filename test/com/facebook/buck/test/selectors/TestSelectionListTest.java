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

import java.util.List;

public class TestSelectionListTest {

  public static final TestDescription CAR_DOORS = new TestDescription(
      "com.example.clown.Car", "testDoors");
  public static final TestDescription SHOE_LACES = new TestDescription(
      "com.example.clown.Shoes", "testLaces");
  public static final TestDescription PM_DECREE = new TestDescription(
      "com.example.clown.PrimeMinisterialDecree", "formAllianceWithClowns");

  @Test
  public void shouldNotFilterTestsThatAreIncluded() {
    TestSelectorList selectomatic = TestSelectorList.buildFrom(
        ImmutableList.of("com.example.clown.Car"));

    assertTrue(selectomatic.isIncluded(CAR_DOORS));
    assertFalse(selectomatic.defaultIsInclusive);
  }

  @Test
  public void shouldFilterTestsThatAreExcluded() {
    TestSelectorList selectomatic = TestSelectorList.buildFrom(
        ImmutableList.of("!com.example.clown.Car"));

    assertFalse(selectomatic.isIncluded(CAR_DOORS));
    assertTrue(selectomatic.defaultIsInclusive);
  }

  @Test
  public void shouldIncludeThingsWeExplicitlyWantToInclude() {
    List<String> rawSelectors = ImmutableList.of(
        "com.example.clown.Car",
        "com.example.clown.Shoes");

    TestSelectorList selectotron = TestSelectorList.buildFrom(rawSelectors);

    assertTrue(selectotron.isIncluded(CAR_DOORS));
    assertTrue(selectotron.isIncluded(SHOE_LACES));
    assertFalse(selectotron.isIncluded(PM_DECREE));
    assertFalse(selectotron.defaultIsInclusive);
  }

  @Test
  public void testExcludeSomeTestsButIncludeByDefault() {
    List<String> rawSelectors = ImmutableList.of(
        "!com.example.clown.PrimeMinisterialDecree",
        "!com.example.clown.Shoes");

    TestSelectorList selectotron = TestSelectorList.buildFrom(rawSelectors);

    assertTrue(selectotron.isIncluded(CAR_DOORS));
    assertFalse(selectotron.isIncluded(SHOE_LACES));
    assertFalse(selectotron.isIncluded(PM_DECREE));
    assertTrue(selectotron.defaultIsInclusive);
  }

  @Test
  public void testExcludeMethodsWithEverythingElseIncluded() {
    List<String> rawSelectors = ImmutableList.of(
        "!#testLaces",
        "!#formAllianceWithClowns");

    TestSelectorList selectotron = TestSelectorList.buildFrom(rawSelectors);

    assertTrue(selectotron.isIncluded(CAR_DOORS));
    assertFalse(selectotron.isIncluded(SHOE_LACES));
    assertFalse(selectotron.isIncluded(PM_DECREE));
  }

  @Test
  public void shouldExplainItself() {
    List<String> rawSelectors = ImmutableList.of(
        "!ClownTest",
        "ShoesTest#testLaces",
        "#testTalent");

    TestSelectorList testSelectorList = TestSelectorList.buildFrom(rawSelectors);

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
    List<String> rawSelectors = ImmutableList.of("!A", "B#c", "#d", "#");

    TestSelectorList testSelectorList = TestSelectorList.buildFrom(rawSelectors);

    ImmutableList<String> expected = ImmutableList.of(
        "exclude class:A method:<any>",
        "include class:B method:c",
        "include class:<any> method:d",
        "include everything else");

    assertEquals(expected, testSelectorList.getExplanation());
  }

}
