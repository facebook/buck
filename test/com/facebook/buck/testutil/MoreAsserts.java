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

package com.facebook.buck.testutil;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import org.junit.Assert;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * Additional assertions that delegate to JUnit assertions, but with better error messages.
 */
public final class MoreAsserts {

  private MoreAsserts() {}

  /**
   * Asserts that two sets have the same contents.
   * On failure, prints a readable diff of the two sets for easy debugging.
   */
  public static <E> void assertSetEquals(Set<E> expected, Set<E> actual) {
    Set<E> missing = Sets.difference(expected, actual);
    Set<E> extra = Sets.difference(actual, expected);
    boolean setsEqual = missing.isEmpty() && extra.isEmpty();
    Assert.assertTrue(
        String.format("%nMissing elements:%n%s%nExtraneous elements:%n%s", missing, extra),
        setsEqual);
  }

  /**
   * @see #assertIterablesEquals(Iterable, Iterable)
   */
  public static <T extends List<?>> void assertListEquals(
      List<?> expected,
      List<?> observed) {
    assertIterablesEquals(expected, observed);
  }

  /**
   * @see #assertIterablesEquals(String, Iterable, Iterable)
   */
  public static <T extends List<?>> void assertListEquals(
      String userMessage,
      List<?> expected,
      List<?> observed) {
    assertIterablesEquals(userMessage, expected, observed);
  }

  /**
   * Equivalent to {@link org.junit.Assert#assertEquals(Object, Object)} except if the assertion
   * fails, the message includes information about where the iterables differ.
   */
  public static <T extends Iterable<?>> void assertIterablesEquals(
      Iterable<?> expected,
      Iterable<?> observed) {
    assertIterablesEquals("" /* userMessage */, expected, observed);
  }

  /**
   * Equivalent to {@link org.junit.Assert#assertEquals(String, Object, Object)} except if the
   * assertion fails, the message includes information about where the iterables differ.
   */
  public static <T extends Iterable<?>> void assertIterablesEquals(
      String userMessage,
      Iterable<?> expected,
      Iterable<?> observed) {
    // The traditional assertEquals() method should be fine if either List is null.
    if (expected == null || observed == null) {
      assertEquals(userMessage, expected, observed);
      return;
    }

    // Compare each item in the list, one at a time.
    Iterator<?> expectedIter = expected.iterator();
    Iterator<?> observedIter = observed.iterator();
    int index = 0;
    while (expectedIter.hasNext()) {
      if (!observedIter.hasNext()) {
        fail(prefixWithUserMessage(userMessage, "Item " + index + " does not exist in the " +
            "observed list: " + expectedIter.next()));
      }
      Object expectedItem = expectedIter.next();
      Object observedItem = observedIter.next();
      assertEquals(
          prefixWithUserMessage(userMessage, "Item " + index + " in the lists should match."),
          expectedItem,
          observedItem);
      ++index;
    }
    if (observedIter.hasNext()) {
      fail(
          prefixWithUserMessage(
              userMessage,
              "Extraneous item %s in the observed list: %s.",
              index,
              observedIter.next()));
    }
  }

  public static <Item, Container extends Iterable<Item>> void assertContainsOne(
      Container container,
      Item expectedItem) {
    assertContainsOne(/* userMessage */ Iterables.toString(container), container, expectedItem);
  }

  public static <Item, Container extends Iterable<Item>> void assertContainsOne(
      String userMessage,
      Container container,
      Item expectedItem) {
    int seen = 0;
    for (Item item : container) {
      if (expectedItem.equals(item)) {
        seen++;
      }
    }
    if (seen < 1) {
      failWith(userMessage, "Item '" + expectedItem + "' not found in container, " +
          "expected to find one.");
    }
    if (seen > 1) {
      failWith(
          userMessage,
          "Found " + Integer.valueOf(seen) + " occurrences of '" + expectedItem +
              "' in container, expected to find only one.");
    }
  }

  /**
   * Asserts that every {@link com.facebook.buck.step.Step} in the observed list is a
   * {@link com.facebook.buck.shell.ShellStep} whose shell
   * command arguments match those of the corresponding entry in the expected list.
   */
  public static void assertShellCommands(
      String userMessage,
      List<String> expected,
      List<Step> observed,
      ExecutionContext context) {
    Iterator<String> expectedIter = expected.iterator();
    Iterator<Step> observedIter = observed.iterator();
    Joiner joiner = Joiner.on(" ");
    while (expectedIter.hasNext() && observedIter.hasNext()) {
      String expectedShellCommand = expectedIter.next();
      Step observedStep = observedIter.next();
      if (!(observedStep instanceof ShellStep)) {
        failWith(userMessage, "Observed command must be a shell command: " + observedStep);
      }
      ShellStep shellCommand = (ShellStep) observedStep;
      String observedShellCommand = joiner.join(shellCommand.getShellCommand(context));
      assertEquals(userMessage, expectedShellCommand, observedShellCommand);
    }

    if (expectedIter.hasNext()) {
      failWith(userMessage, "Extra expected command: " + expectedIter.next());
    }

    if (observedIter.hasNext()) {
      failWith(userMessage, "Extra observed command: " + observedIter.next());
    }
  }

  /**
   * Invokes the {@link Step#getDescription(ExecutionContext)} method on each of the observed steps
   * to create a list of strings and compares it to the expected value.
   */
  public static void assertSteps(
      String userMessage,
      List<String> expectedStepDescriptions,
      List<Step> observedSteps,
      final ExecutionContext executionContext) {
    ImmutableList<String> commands = FluentIterable
        .from(observedSteps)
        .transform(new Function<Step, String>() {
          @Override
          public String apply(Step step) {
            return step.getDescription(executionContext);
          }
        })
        .toList();
    assertListEquals(
        userMessage,
        expectedStepDescriptions,
        commands);
  }

  public static void assertDepends(String userMessage, BuildRule rule, BuildRule dep) {
    assertDepends(userMessage, rule, dep.getBuildTarget());
  }

  public static void assertDepends(String userMessage, BuildRule rule, BuildTarget dep) {
    assertDepends(userMessage, rule.getDeps(), dep);
  }

  public static void assertDepends(
      String userMessage,
      Collection<BuildRule> ruleDeps,
      BuildTarget dep) {
    for (BuildRule realDep : ruleDeps) {
      BuildTarget target = realDep.getBuildTarget();
      if (target.equals(dep)) {
        return;
      }
    }
    fail(userMessage);
  }

  public static void assertNotDepends(
      String userMessage,
      Collection<BuildRule> ruleDeps,
      BuildTarget dep) {
    for (BuildRule realDep : ruleDeps) {
      BuildTarget target = realDep.getBuildTarget();
      if (target.equals(dep)) {
        fail(userMessage);
      }
    }
  }

  public static <T> void assertOptionalValueEquals(
      String userMessage,
      T expectedValue,
      Optional<T> optionalValue) {
    if (!optionalValue.isPresent()) {
      failWith(userMessage, "Optional value is not present.");
    }

    assertEquals(userMessage, expectedValue, optionalValue.get());
  }

  private static String prefixWithUserMessage(
      @Nullable String userMessage,
      String message,
      Object... formatArgs) {
    return (userMessage == null ? "" : userMessage + " ") + String.format(message, formatArgs);
  }

  private static void failWith(@Nullable String userMessage, String message) {
    fail(prefixWithUserMessage(userMessage, message));
  }
}
