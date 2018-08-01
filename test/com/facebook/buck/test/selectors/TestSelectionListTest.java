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
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import org.junit.Test;

public class TestSelectionListTest {

  public static final String CAR_CLASS = "com.example.clown.Car";
  public static final String SHOES_CLASS = "com.example.clown.Shoes";
  public static final String PM_CLASS = "com.example.clown.PrimeMinisterialDecree";

  public static final TestDescription CAR_DOORS_TEST = new TestDescription(CAR_CLASS, "testDoors");
  public static final TestDescription SHOE_LACES_TEST =
      new TestDescription(SHOES_CLASS, "testLaces");
  public static final TestDescription PM_DECREE_TEST =
      new TestDescription(PM_CLASS, "formAllianceWithClowns");

  public static final TestDescription NOT_LIKELY_TO_MATCH =
      new TestDescription("abc.xyz", "fooBarBaz");

  private void assertDefaultIsIncluded(TestSelectorList testSelectorList) {
    assertTrue(testSelectorList.isIncluded(NOT_LIKELY_TO_MATCH));
  }

  private void assertDefaultIsExcluded(TestSelectorList testSelectorList) {
    assertFalse(testSelectorList.isIncluded(NOT_LIKELY_TO_MATCH));
  }

  @Test
  public void shouldNotFilterTestsThatAreIncluded() {
    TestSelectorList selectomatic =
        new TestSelectorList.Builder().addRawSelectors(CAR_CLASS).build();

    assertTrue(selectomatic.isIncluded(CAR_DOORS_TEST));
    assertDefaultIsExcluded(selectomatic);
  }

  @Test
  public void shouldFilterTestsThatAreExcluded() {
    TestSelectorList selectomatic =
        new TestSelectorList.Builder().addRawSelectors("!" + CAR_CLASS).build();

    assertFalse(selectomatic.isIncluded(CAR_DOORS_TEST));
    assertDefaultIsIncluded(selectomatic);
  }

  @Test
  public void shouldIncludeThingsWeExplicitlyWantToInclude() {
    TestSelectorList selectotron =
        new TestSelectorList.Builder()
            .addRawSelectors(CAR_CLASS)
            .addRawSelectors(SHOES_CLASS)
            .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS_TEST));
    assertTrue(selectotron.isIncluded(SHOE_LACES_TEST));
    assertFalse(selectotron.isIncluded(PM_DECREE_TEST));
    assertDefaultIsExcluded(selectotron);
  }

  @Test
  public void testExcludeSomeTestsButIncludeByDefault() {
    TestSelectorList selectotron =
        new TestSelectorList.Builder()
            .addRawSelectors("!" + PM_CLASS)
            .addRawSelectors("!" + SHOES_CLASS)
            .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS_TEST));
    assertFalse(selectotron.isIncluded(SHOE_LACES_TEST));
    assertFalse(selectotron.isIncluded(PM_DECREE_TEST));
    assertDefaultIsIncluded(selectotron);
  }

  @Test
  public void testExcludeMethodsWithEverythingElseIncluded() {
    TestSelectorList selectotron =
        new TestSelectorList.Builder()
            .addRawSelectors("!#testLaces")
            .addRawSelectors("!#formAllianceWithClowns")
            .build();

    assertTrue(selectotron.isIncluded(CAR_DOORS_TEST));
    assertFalse(selectotron.isIncluded(SHOE_LACES_TEST));
    assertFalse(selectotron.isIncluded(PM_DECREE_TEST));
  }

  @Test
  public void shouldExplainItself() {
    TestSelectorList testSelectorList =
        new TestSelectorList.Builder()
            .addRawSelectors("!ClownTest")
            .addRawSelectors("ShoesTest#testLaces")
            .addRawSelectors("#testTalent")
            .build();

    assertDefaultIsExcluded(testSelectorList);

    ImmutableList<String> expected =
        ImmutableList.of(
            "exclude class:ClownTest$ method:<any>",
            "include class:ShoesTest$ method:testLaces$",
            "include class:<any> method:testTalent$",
            "exclude everything else");

    assertEquals(expected, testSelectorList.getExplanation());
  }

  @Test
  public void shouldCollapseCatchAllFinalSelector() {
    TestSelectorList testSelectorList =
        new TestSelectorList.Builder().addRawSelectors("!A", "B#c", "#d", "#").build();

    ImmutableList<String> expected =
        ImmutableList.of(
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

    TestSelectorList testSelectorList =
        new TestSelectorList.Builder().loadFromFile(tempFile).build();

    assertFalse(testSelectorList.isIncluded(CAR_DOORS_TEST));
    assertTrue(testSelectorList.isIncluded(SHOE_LACES_TEST));
    assertTrue(testSelectorList.isIncluded(PM_DECREE_TEST));
  }

  @Test
  public void shouldAlwaysIncludeTestsWhenUsingTheEmptySelectorList() {
    TestSelectorList testSelectorList = TestSelectorList.empty();
    TestDescription description = new TestDescription("com.example.ClassName", "methodName");
    assertTrue(
        "The empty list should include everything", testSelectorList.isIncluded(description));
  }

  @Test
  public void possiblyIncludesAnyClassNameWhenListIsEmpty() {
    TestSelectorList emptyList = TestSelectorList.empty();
    assertTrue(emptyList.possiblyIncludesClassName("Foo"));
  }

  @Test
  public void includesOnlyProvidedClassNameWhenListIsSimpleSelector() {
    TestSelectorList emptyList =
        new TestSelectorList.Builder().addSimpleTestSelector("com.example.Foo,bar").build();
    assertTrue(emptyList.possiblyIncludesClassName("com.example.Foo"));
    assertFalse(emptyList.possiblyIncludesClassName("com.example.Bar"));
  }

  @Test
  public void includesOuterClassName() {
    TestSelectorList testList =
        new TestSelectorList.Builder().addSimpleTestSelector("com.example.Foo$Other,bar").build();
    assertTrue(testList.possiblyIncludesClassName("com.example.Foo"));
    assertFalse(testList.possiblyIncludesClassName("com.example.Bar"));
    assertFalse(testList.possiblyIncludesClassName("com.example.Other"));
  }

  @Test
  public void possiblyIncludesClassNameWhenClassMatchesAndIsIncluded() {
    TestSelectorList emptyList =
        new TestSelectorList.Builder()
            .addRawSelectors("!Foo#skipMe", "!#andSkipMe", "Foo", "Bar#baz", "!#")
            .build();
    assertTrue(emptyList.possiblyIncludesClassName("Foo"));
  }

  @Test
  public void possiblyIncludesClassNameWhenMethodMatchesAndIsIncluded() {
    TestSelectorList emptyList =
        new TestSelectorList.Builder()
            .addRawSelectors("!Foo#skipMe", "!#andSkipMe", "Foo", "Bar#baz", "!#")
            .build();
    assertTrue(emptyList.possiblyIncludesClassName("Bar"));
  }

  @Test
  public void possiblyIncludesClassNameWhenNestedClass() {
    TestSelectorList emptyList =
        new TestSelectorList.Builder()
            .addRawSelectors("Foo", "Bar\\$Inner")
            .addSimpleTestSelector("com.example.Faz$Inner,test")
            .build();
    assertTrue(emptyList.possiblyIncludesClassName("Bar"));
    assertFalse(emptyList.possiblyIncludesClassName("Bar2"));

    assertTrue(emptyList.possiblyIncludesClassName("Foo"));

    assertTrue(emptyList.possiblyIncludesClassName("com.example.Faz"));
  }

  @Test
  public void possiblyIncludesClassNameWhenNothingMatchesAndDefaultIsInclusion() {
    TestSelectorList emptyList =
        new TestSelectorList.Builder().addRawSelectors("!Foo", "!Bar", "#").build();
    assertTrue(emptyList.possiblyIncludesClassName("Baz"));
  }

  @Test
  public void doesNotPossiblyIncludeClassNameWhenNothingMatchesAndDefaultIsExclusion() {
    TestSelectorList emptyList =
        new TestSelectorList.Builder().addRawSelectors("Foo", "Bar", "!#").build();
    assertFalse(emptyList.possiblyIncludesClassName("Baz"));
  }
}
