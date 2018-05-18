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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

/**
 * A collection of {@link PatternTestSelector} instances which, as a group, can decide whether or
 * not to include a given {@link TestDescription}.
 */
public class TestSelectorList {

  public static final TestSelectorList EMPTY = TestSelectorList.builder().build();

  /**
   * Test selector strings are parsed in two places: (i) by "buck test" when it is first run, to
   * validate that the selectors make sense; and (ii) by the JUnitStep's JUnitRunner, which is what
   * actually does the test selecting.
   *
   * <p>We keep a list of the raw selectors used to build out List of TestSelectors so that they can
   * be passed from (i) to (ii). This is expensive in that it wastes time re-parsing selectors, but
   * it means that if the input is an "@/tmp/long-list-of-tests.txt" then re-using that terse
   * argument keeps the "JUnitSteps" Junit java command line nice and short.
   */
  private final List<TestSelector> testSelectors;

  private final boolean defaultIsInclusive;

  private TestSelectorList(List<TestSelector> testSelectors, boolean defaultIsInclusive) {
    this.testSelectors = testSelectors;
    this.defaultIsInclusive = defaultIsInclusive;
  }

  private TestSelector defaultSelector() {
    return defaultIsInclusive
        ? PatternTestSelector.INCLUDE_EVERYTHING
        : PatternTestSelector.EXCLUDE_EVERYTHING;
  }

  public TestSelector findSelector(TestDescription description) {
    for (TestSelector testSelector : testSelectors) {
      if (testSelector.matches(description)) {
        return testSelector;
      }
    }
    return defaultSelector();
  }

  public boolean isIncluded(TestDescription description) {
    return findSelector(description).isInclusive();
  }

  /**
   * Returns true if it is *possible* for the given classname to include tests.
   *
   * <p>Before we go through the hassle of loading a class, confirm that it's possible for it to run
   * tests.
   */
  public boolean possiblyIncludesClassName(String className) {
    for (TestSelector testSelector : testSelectors) {
      if (testSelector.matchesClassName(className)) {
        if (testSelector.isInclusive()) {
          return true;
        }
        if (testSelector.isMatchAnyMethod()) {
          return false;
        }
      }
    }
    return defaultSelector().isInclusive();
  }

  public List<String> getExplanation() {
    List<String> lines = new ArrayList<>();
    for (TestSelector testSelector : testSelectors) {
      lines.add(testSelector.getExplanation());
    }
    lines.add(defaultSelector().getExplanation());
    return lines;
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
   * Build a new {@link TestSelectorList} from a list of strings, each of which is parsed by {@link
   * PatternTestSelector}.
   *
   * <p>If any of the selectors is an inclusive selector, everything else will be excluded.
   * Conversely, if all of the selectors are exclusive, then everything else will be included by
   * default.
   */
  public static class Builder {

    private final List<TestSelector> testSelectors;

    protected Builder() {
      testSelectors = new ArrayList<>();
    }

    private Builder addRawSelector(String rawSelector) {
      if (rawSelector.charAt(0) == ':') {
        try {
          String pathString = rawSelector.substring(1);
          if (pathString.isEmpty()) {
            throw new TestSelectorParseException("Doesn't mention a path!");
          }
          File file = new File(pathString);
          loadFromFile(file);
        } catch (TestSelectorParseException | IOException e) {
          String message =
              String.format("Error with test-selector '%s': %s", rawSelector, e.getMessage());
          throw new RuntimeException(message, e);
        }
        return this;
      } else {
        TestSelector testSelector = PatternTestSelector.buildFromSelectorString(rawSelector);
        this.testSelectors.add(testSelector);
      }
      return this;
    }

    public Builder addRawSelectors(String... rawSelectors) {
      return addRawSelectors(Arrays.asList(rawSelectors));
    }

    public Builder addRawSelectors(Collection<String> rawTestSelectors) {
      rawTestSelectors.forEach(this::addRawSelector);
      return this;
    }

    public Builder addSimpleTestSelector(String simpleSelector) {
      String[] selectorParts = simpleSelector.split(",");
      if (selectorParts.length != 2) {
        throw new IllegalArgumentException();
      }
      String className = selectorParts[0];
      String methodName = selectorParts[1];
      this.testSelectors.add(new SimpleTestSelector(className, methodName));
      return this;
    }

    public Builder addBase64EncodedTestSelector(String b64Selector) {
      String[] selectorParts = b64Selector.split(",");
      if (selectorParts.length != 2) {
        throw new IllegalArgumentException();
      }
      String className = selectorParts[0];
      String methodName = selectorParts[1];
      Base64.Decoder decoder = Base64.getDecoder();
      String decodedClassName = new String(decoder.decode(className), StandardCharsets.UTF_8);
      String decodedMethodName = new String(decoder.decode(methodName), StandardCharsets.UTF_8);
      this.testSelectors.add(new SimpleTestSelector(decodedClassName, decodedMethodName));
      return this;
    }

    Builder loadFromFile(File file) throws IOException {
      try (FileReader tempReader = new FileReader(file);
          BufferedReader in = new BufferedReader(tempReader)) {
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
      boolean defaultIsInclusive = true;
      List<TestSelector> selectorsToUse = new ArrayList<>();
      for (TestSelector testSelector : testSelectors) {
        // Default to being inclusive only if all selectors are *exclusive*.
        defaultIsInclusive = defaultIsInclusive && !testSelector.isInclusive();
        // If a selector is universal (matches every class and method), no need to look further
        if (testSelector.isMatchAnyClass() && testSelector.isMatchAnyMethod()) {
          defaultIsInclusive = testSelector.isInclusive();
          break;
        }
        selectorsToUse.add(testSelector);
      }
      return new TestSelectorList(selectorsToUse, defaultIsInclusive);
    }
  }
}
