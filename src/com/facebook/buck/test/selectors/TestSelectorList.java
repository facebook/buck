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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * A collection of {@link TestSelector} instances which, as a group, can decide whether or not to
 * include a given {@link TestDescription}.
 */
public class TestSelectorList {

  private static final TestSelectorList EMPTY = TestSelectorList.builder().build();

  /**
   * Test selector strings are parsed in two places: (i) by "buck test" when it is first run, to
   * validate that the selectors make sense; and (ii) by the JUnitStep's JUnitRunner, which
   * is what actually does the test selecting.
   * <p>
   * We keep a list of the raw selectors used to build out List of TestSelectors so that they can
   * be passed from (i) to (ii).  This is expensive in that it wastes time re-parsing selectors, but
   * it means that if the input is an "@/tmp/long-list-of-tests.txt" then re-using that terse
   * argument keeps the "JUnitSteps" Junit java command line nice and short.
   */
  private final List<TestSelector> testSelectors;
  final boolean defaultIsInclusive;

  private TestSelectorList(
      List<TestSelector> testSelectors,
      boolean defaultIsInclusive) {
    this.testSelectors = testSelectors;
    this.defaultIsInclusive = defaultIsInclusive;
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
    List<String> rawSelectors = new ArrayList<>();
    for (TestSelector testSelector : testSelectors) {
      String rawSelector = testSelector.getRawSelector();
      rawSelectors.add(rawSelector);
    }
    return rawSelectors;
  }

  public boolean isEmpty() {
    return testSelectors.isEmpty();
  }

  public static TestSelectorList empty() {
    return EMPTY;
  }

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Build a new {@link TestSelectorList} from a list of strings, each of which is parsed by
   * {@link TestSelector}.
   *
   * If any of the selectors is an inclusive selector, everything else will be excluded.
   * Conversely, if all of the selectors are exclusive, then everything else will be included by
   * default.
   *
   */
  public static class Builder {

    private final List<TestSelector> testSelectors;

    protected Builder() {
      testSelectors = new ArrayList<>();
    }

    private Builder addRawSelector(String rawSelector) {
      if (rawSelector.charAt(0) == '@') {
        try {
          String pathString = rawSelector.substring(1);
          if (pathString.isEmpty()) {
            throw new TestSelectorParseException("Doesn't mention a path!");
          }
          File file = new File(pathString);
          loadFromFile(file);
        } catch (TestSelectorParseException|IOException e) {
          String message = String.format("Error with test-selector '%s': %s",
              rawSelector, e.getMessage());
          throw new RuntimeException(message, e);
        }
        return this;
      } else {
        TestSelector testSelector = TestSelector.buildFromSelectorString(rawSelector);
        this.testSelectors.add(testSelector);
      }
      return this;
    }

    public Builder addRawSelectors(String... rawSelectors) {
      return addRawSelectors(Arrays.asList(rawSelectors));
    }

    public Builder addRawSelectors(Collection<String> rawTestSelectors) {
      for (String rawTestSelector : rawTestSelectors) {
        addRawSelector(rawTestSelector);
      }
      return this;
    }

    Builder loadFromFile(File file) throws IOException {
      try (
        FileReader tempReader = new FileReader(file);
        BufferedReader in = new BufferedReader(tempReader)
      ) {
        String line;
        int lineNumber = 1;

        while ((line = in.readLine()) != null) {
          try {
            addRawSelector(line.trim());
            lineNumber++;
          } catch (TestSelectorParseException e) {
            String message =
                String.format("Test selector error in %s at line %d", file, lineNumber);
            throw new TestSelectorParseException(message, e);
          }
        }
      } catch (IOException e) {
        throw e;
      }
      return this;
    }

    public TestSelectorList build() {
      // Default to being inclusive only if all selectors are *exclusive*.
      boolean defaultIsInclusive = true;
      for (TestSelector testSelector : testSelectors) {
        if (testSelector.isInclusive()) {
          defaultIsInclusive = false;
          break;
        }
      }
      return new TestSelectorList(
          testSelectors,
          defaultIsInclusive);
    }
  }
}
