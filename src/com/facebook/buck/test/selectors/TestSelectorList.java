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

import java.util.ArrayList;
import java.util.List;

/**
 * A collection of {@link TestSelector} instances which, as a group, can decide whether or not to
 * include a given {@link TestDescription}.
 */
public class TestSelectorList {

  private final List<String> rawSelectors;
  private final List<TestSelector> testSelectors;
  final boolean defaultIsInclusive;

  TestSelectorList(
      List<String> rawSelectors,
      List<TestSelector> testSelectors,
      boolean defaultIsInclusive) {
    this.rawSelectors = rawSelectors;
    this.testSelectors = testSelectors;
    this.defaultIsInclusive = defaultIsInclusive;
  }

  /**
   * Build a new {@link TestSelectorList} from a list of strings, each of which is parsed by
   * {@link TestSelector}.
   *
   * If any of the selectors is an inclusive selector, everything else will be excluded.
   * Conversely, if all of the selectors are exclusive, then everything else will be included by
   * default.
   *
   * @param rawSelectors A list of strings that will be parsed into {@link TestSelector} instances.
   * @return
   */
  public static TestSelectorList buildFrom(List<String> rawSelectors) {
    if (rawSelectors.size() == 0) {
      throw new IllegalArgumentException("You must give at least one rawSelector, none given!");
    }
    List<TestSelector> testSelectors = new ArrayList<>();
    boolean defaultIsInclusive = true;
    for (String rawSelector : rawSelectors) {
      TestSelector testSelector;
      testSelector = TestSelector.buildFrom(rawSelector);
      // Default to being inclusive only if all selectors are *exclusive*.
      defaultIsInclusive = defaultIsInclusive && !testSelector.isInclusive();
      testSelectors.add(testSelector);
    }
    // If we are given a list of exclusion rules, default to including everything else!
    return new TestSelectorList(rawSelectors, testSelectors, defaultIsInclusive);
  }

  public boolean isIncluded(TestDescription description) {
    for (TestSelector testSelector : testSelectors) {
      if (testSelector.matches(description)) {
        return testSelector.isInclusive();
      }
    }

    return defaultIsInclusive;
  }

  public List<String> getExplanation() {
    List<String> lines = new ArrayList<>();
    for (TestSelector testSelector : testSelectors) {
      lines.add(testSelector.getExplanation());
    }

    // If the last selector matches everything, derive our default behavior from that test selector
    // and replace the last line of explanation.
    int lastIndex = testSelectors.size() - 1;
    TestSelector lastTestSelector = testSelectors.get(lastIndex);
    if (lastTestSelector.isMatchAnyClass() && lastTestSelector.isMatchAnyMethod()) {
      String lastLine = formatEverythingElseLine(lastTestSelector.isInclusive());
      lines.set(lastIndex, lastLine);
    } else {
      // Otherwise describe our default behavior.
      lines.add(formatEverythingElseLine(defaultIsInclusive));
    }
    return lines;
  }

  private String formatEverythingElseLine(boolean isInclusive) {
    return String.format("%s everything else", isInclusive ? "include" : "exclude");
  }

  public List<String> getRawSelectors() {
    return rawSelectors;
  }
}
