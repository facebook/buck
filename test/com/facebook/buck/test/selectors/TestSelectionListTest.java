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

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class TestSelectionListTest {

  public static final String CAR_CLASS = "com.example.clown.Car";
  public static final String SHOES_CLASS = "com.example.clown.Shoes";
  public static final String PM_CLASS = "com.example.clown.PrimeMinisterialDecree";

  public static final TestDescription CAR_DOORS_TEST =
      new TestDescription(CAR_CLASS, "testDoors");
  public static final TestDescription SHOE_LACES_TEST =
      new TestDescription(SHOES_CLASS, "testLaces");
  public static final TestDescription PM_DECREE_TEST =
      new TestDescription(PM_CLASS, "formAllianceWithClowns");

  @Test
  public void shouldNotFilterTestsThatAreIncluded() {
    TestSelectorList selectomatic = new TestSelectorList.Builder()
        .addRawSelectors(CAR_CLASS)
        .build();

    assertTrue(selectomatic.isIncluded(CAR_DOORS_TEST));
    assertFalse(selectomatic.defaultIsInclusive);
  }

  @Test
  public void shouldFilterTestsThatAreExcluded() {
    TestSelectorList selectomatic = new TestSelectorList.Builder()
        .addRawSelectors("!" + CAR_CLASS)
        .build();

    assertFalse(selectomatic.isIncluded(CAR_DOORS_TEST));
    assertTrue(selectomatic.defaultIsInclusive);
  }

  @Test
  public void shouldIncludeThingsWeExplicitlyWantToInclude() {
    TestSelectorList selectotron = new TestSelectorList.Builder()
        .addRawSelectors(CAR_CLASS)
        .addRawSelectors(SHOES_CLASS)
        .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS_TEST));
    assertTrue(selectotron.isIncluded(SHOE_LACES_TEST));
    assertFalse(selectotron.isIncluded(PM_DECREE_TEST));
    assertFalse(selectotron.defaultIsInclusive);
  }

  @Test
  public void testExcludeSomeTestsButIncludeByDefault() {
    TestSelectorList selectotron = new TestSelectorList.Builder()
        .addRawSelectors("!" + PM_CLASS)
        .addRawSelectors("!" + SHOES_CLASS)
        .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS_TEST));
    assertFalse(selectotron.isIncluded(SHOE_LACES_TEST));
    assertFalse(selectotron.isIncluded(PM_DECREE_TEST));
    assertTrue(selectotron.defaultIsInclusive);
  }

  @Test
  public void testExcludeMethodsWithEverythingElseIncluded() {
    TestSelectorList selectotron = new TestSelectorList.Builder()
        .addRawSelectors("!#testLaces")
        .addRawSelectors("!#formAllianceWithClowns")
        .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS_TEST));
    assertFalse(selectotron.isIncluded(SHOE_LACES_TEST));
    assertFalse(selectotron.isIncluded(PM_DECREE_TEST));
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
        "exclude class:ClownTest$ method:<any>",
        "include class:ShoesTest$ method:testLaces$",
        "include class:<any> method:testTalent$",
        "exclude everything else");

    assertEquals(expected, testSelectorList.getExplanation());
  }

  @Test
  public void shouldCollapseCatchAllFinalSelector() {
    TestSelectorList testSelectorList = new TestSelectorList.Builder()
        .addRawSelectors("!A", "B#c", "#d", "#")
        .build();

    ImmutableList<String> expected = ImmutableList.of(
        "exclude class:A$ method:<any>",
        "include class:B$ method:c$",
        "include class:<any> method:d$",
        "include everything else");

    assertEquals(expected, testSelectorList.getExplanation());
  }

  @Test
  public void shouldSelectFromFile() throws IOException {
    File tempFile = File.createTempFile("test-selectors", ".txt");
    FileWriter fileWriter = new FileWriter(tempFile);
    fileWriter.write("!" + CAR_CLASS + "\n");
    fileWriter.write(SHOES_CLASS + "\n");
    fileWriter.write("#\n");
    fileWriter.close();

    TestSelectorList testSelectorList = new TestSelectorList.Builder()
        .loadFromFile(tempFile)
        .build();

    assertFalse(testSelectorList.isIncluded(CAR_DOORS_TEST));
    assertTrue(testSelectorList.isIncluded(SHOE_LACES_TEST));
    assertTrue(testSelectorList.isIncluded(PM_DECREE_TEST));
  }

  @Test
  public void shouldAlwaysIncludeTestsWhenUsingTheEmptySelectorList() {
    TestSelectorList testSelectorList = TestSelectorList.empty();
    TestDescription description = new TestDescription("com.example.ClassName", "methodName");
    assertTrue(
        "The empty list should include everything",
        testSelectorList.isIncluded(description));
  }
}
