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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class TestSelectorTest {

  @Test
  public void shouldConstructAnSelectorThatIsNotInclusive() {
    TestSelector selector = TestSelector.buildFromSelectorString("!com.example.clown.Car");

    assertFalse(selector.isInclusive());
  }

  @Test
  public void shouldAllBeInclusiveSelectors() {
    assertIsInclusive("#");
    assertIsInclusive("Car#Wheels");
    assertIsInclusive("com.example.clown.Car");
    assertIsInclusive("com.example.clown.Car#");
    assertIsInclusive("com.example.clown.Car#testWheelsAreInadequateForDayToDayUse");
    assertIsInclusive("#testWheelsAreInadequateForDayToDayUse");
  }

  private void assertIsInclusive(String rawSelector) {
    TestSelector testSelector = TestSelector.buildFromSelectorString(rawSelector);
    String message = String.format("'%s' is an inclusive test selector", rawSelector);
    assertTrue(message, testSelector.isInclusive());
  }

  @Test(expected = RuntimeException.class)
  public void shouldThrowOnEmptyInput() {
    TestSelector.buildFromSelectorString("");
  }

  @Test(expected = RuntimeException.class)
  public void shouldThrowOnNullInput() {
    TestSelector.buildFromSelectorString(null);
  }

  @Test
  public void shouldThrowOnMultiHashInput() {
    assertThrowsParseException("##");
    assertThrowsParseException("a##");
    assertThrowsParseException("#b#");
    assertThrowsParseException("a#b#");
    assertThrowsParseException("##c");
    assertThrowsParseException("a##c");
    assertThrowsParseException("#b#c");
    assertThrowsParseException("a#b#c");
  }

  private void assertThrowsParseException(String rawSelector) {
    try {
      TestSelector.buildFromSelectorString(rawSelector);
    } catch (TestSelectorParseException e) {
      return;
    }
    fail(String.format("'%s' should throw a parse exception", rawSelector));
  }

  @Test(expected = TestSelectorParseException.class)
  public void shouldThrowOnUnparseableRegularExpression() {
    TestSelector.buildFromSelectorString("Cloooo(#)ooowntown");
  }

  @Test
  public void shouldSelectAClassByItself() {
    TestDescription description = new TestDescription("com.example.clown.Car", null);
    TestSelector selector = TestSelector.buildFromSelectorString("com.example.clown.Car");
    assertTrue(selector.matches(description));
  }

  @Test
  public void shouldAllIncludeFlowerDescription() {
    TestDescription desc = new TestDescription("com.example.clown.Flower", "testSquirtySquirt");
    assertMatchesTestDescription(desc, "#");
    assertMatchesTestDescription(desc, "Flower");
    assertMatchesTestDescription(desc, "Flower#");
    assertMatchesTestDescription(desc, "#Squirt");
    assertMatchesTestDescription(desc, "Flower#Squirt");
    assertMatchesTestDescription(desc, "Flow.+#testSq...t.+");
    assertMatchesTestDescription(desc, "Flow.+#Sq...t.+");
    assertMatchesTestDescription(desc, "^com.+#^test.+");
    assertMatchesTestDescription(desc, "com.example.clown.Flower");
    assertMatchesTestDescription(desc, "com.example.clown.Flower#");
    assertMatchesTestDescription(desc, "com.example.clown.Flower#testSquirtySquirt");
    assertMatchesTestDescription(desc, "#testSquirtySquirt");
  }

  private void assertMatchesTestDescription(TestDescription description, String rawSelector) {
    TestSelector testSelector = TestSelector.buildFromSelectorString(rawSelector);
    String message = String.format("Test selector '%s' should match class:%s method:%s",
        rawSelector,
        description.getClassName(),
        description.getMethodName());
    assertTrue(message, testSelector.matches(description));
  }

  @Test
  public void shouldSelectOnMethodNameAndMethodNameAlone() {
    TestDescription desc1 = new TestDescription("com.example.clown.Car", "testIsComical");
    TestDescription desc2 = new TestDescription("com.example.clown.Flower", "testIsComical");
    TestDescription desc3 = new TestDescription("com.example.clown.Shoes", "testIsDeadlySerious");

    TestSelector selector = TestSelector.buildFromSelectorString("#testIsComical");

    assertTrue(selector.matches(desc1));
    assertTrue(selector.matches(desc2));
    assertFalse(selector.matches(desc3));
  }
}
