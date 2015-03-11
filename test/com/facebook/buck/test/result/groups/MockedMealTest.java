/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.test.result.groups;

import static com.facebook.buck.test.result.groups.MockingDSL.deps;
import static com.facebook.buck.test.result.groups.MockingDSL.failTestsAndCapture;
import static com.facebook.buck.test.result.groups.MockingDSL.mockLibrary;
import static com.facebook.buck.test.result.groups.MockingDSL.mockTest;
import static com.facebook.buck.test.result.groups.MockingDSL.passTests;
import static com.facebook.buck.test.result.groups.MockingDSL.sourceUnderTest;
import static org.easymock.EasyMock.newCapture;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.TestRule;
import com.facebook.buck.test.TestResults;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.easymock.Capture;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MockedMealTest {

  private final BuildRule meal = mockLibrary("//src", "meal");
  private final BuildRule fish = mockLibrary("//src", "fish");
  private final BuildRule meat = mockLibrary("//src", "meat");
  private final BuildRule oven = mockLibrary("//src", "oven");
  private final BuildRule wood = mockLibrary("//src", "wood");
  private final BuildRule fire = mockLibrary("//src", "fire");
  private final BuildRule cake = mockLibrary("//src", "cake");
  private final BuildRule eggs = mockLibrary("//src", "eggs");
  private final BuildRule beer = mockLibrary("//src", "beer");
  private final BuildRule hops = mockLibrary("//src", "hops");
  private final BuildRule fork = mockLibrary("//src", "fork");


  private final TestRule mealTest = mockTest("//test", "meal");
  private final TestRule fishTest = mockTest("//test", "fish");
  private final TestRule meatTest = mockTest("//test", "meat");
  private final TestRule ovenTest = mockTest("//test", "oven");
  private final TestRule woodTest = mockTest("//test", "wood");
  private final TestRule fireTest = mockTest("//test", "fire");
  private final TestRule cakeTest = mockTest("//test", "cake");
  private final TestRule eggsTest = mockTest("//test", "eggs");
  private final TestRule beerTest = mockTest("//test", "beer");
  private final TestRule hopsTest = mockTest("//test", "hops");

  // fork has no forkTest, showing the depending on something that isn't tested doesn't block the
  // grouping of test results.

  private final Set<BuildRule> allLibraries = ImmutableSet.of(
      meal, fish, meat, oven, wood, fire, cake, eggs, beer, hops, fork);

  private final Set<TestRule> allTests = ImmutableSet.of(
      mealTest,
      fishTest, meatTest,
      ovenTest, woodTest, fireTest,
      cakeTest, eggsTest,
      beerTest, hopsTest);

  @Before
  public void setUp() {
    /*
     * Meal(
     *   Fish(
     *     Meat(),
     *     Oven(
     *       Wood(),
     *       Fire()
     *     )
     *   ),
     *   Cake(
     *     Eggs(),
     *     Oven(...)
     *   ),
     *   Beer(
     *     Hops(),
     *   ),
     *   Fork()
     * )
     */

    // Leaves
    deps(meat);
    deps(wood);
    deps(fire);
    deps(eggs);
    deps(hops);
    deps(fork);

    // Foods
    deps(oven, wood, fire);
    deps(fish, meat, oven);
    deps(cake, eggs, oven);
    deps(beer, hops);
    deps(meal, fish, cake, beer);

    replay(allLibraries.toArray());

    sourceUnderTest(mealTest, meal);
    sourceUnderTest(fishTest, fish);
    sourceUnderTest(meatTest, meat);
    sourceUnderTest(ovenTest, oven);
    sourceUnderTest(woodTest, wood);
    sourceUnderTest(fireTest, fire);
    sourceUnderTest(cakeTest, cake);
    sourceUnderTest(eggsTest, eggs);
    sourceUnderTest(beerTest, beer);
    sourceUnderTest(hopsTest, hops);

    replay(allTests.toArray());
  }

  @Test
  public void simpleOneTestCase() {
    TestResultsGrouper grouper = new TestResultsGrouper(ImmutableList.of(fireTest));

    assertGroupedTestResults(grouper.post(fireTest, passTests()), fireTest);
  }

  @Test
  public void bottomUpShouldYieldResultsAtEveryStep() {
    List<TestRule> tests = ImmutableList.of(mealTest, cakeTest, eggsTest);
    TestResultsGrouper grouper = new TestResultsGrouper(tests);

    assertGroupedTestResults(grouper.post(eggsTest, passTests()), eggsTest);
    assertGroupedTestResults(grouper.post(cakeTest, passTests()), cakeTest);
    assertGroupedTestResults(grouper.post(mealTest, passTests()), mealTest);
  }

  @Test
  public void topDownShouldBlockEverythingUntilFinalTest() {
    List<TestRule> tests = ImmutableList.of(mealTest, cakeTest, eggsTest);
    TestResultsGrouper grouper = new TestResultsGrouper(tests);

    assertGroupedTestResults(grouper.post(mealTest, passTests()));
    assertGroupedTestResults(grouper.post(cakeTest, passTests()));
    assertGroupedTestResults(grouper.post(eggsTest, passTests()), mealTest, cakeTest, eggsTest);
  }

  @Test
  public void shouldWorkWithLeafTestInTheMiddle() {
    List<TestRule> tests = ImmutableList.of(mealTest, cakeTest, eggsTest);
    TestResultsGrouper grouper = new TestResultsGrouper(tests);

    assertGroupedTestResults(grouper.post(mealTest, passTests()));
    assertGroupedTestResults(grouper.post(eggsTest, passTests()), eggsTest);
    assertGroupedTestResults(grouper.post(cakeTest, passTests()), mealTest, cakeTest);
  }

  @Test
  public void shouldSetFailedDependenciesBitInAPair() {
    List<TestRule> tests = ImmutableList.of(beerTest, hopsTest);
    TestResultsGrouper grouper = new TestResultsGrouper(tests);

    Capture<Boolean> isBeerDependenciesPass = newCapture();
    Capture<Boolean> isHopsDependenciesPass = newCapture();

    TestResults beerTestResults = failTestsAndCapture(isBeerDependenciesPass);
    TestResults hopsTestResults = failTestsAndCapture(isHopsDependenciesPass);

    assertGroupedTestResults(grouper.post(beerTest, beerTestResults));
    assertGroupedTestResults(grouper.post(hopsTest, hopsTestResults), beerTest, hopsTest);

    assertFalse("Beer should have a failed dependency (hops)", isBeerDependenciesPass.getValue());
    assertTrue("Hops has no failed dependencies", isHopsDependenciesPass.getValue());
  }

  @Test
  public void shouldSetFailedDependenciesBitsInATriple() {
    List<TestRule> tests = ImmutableList.of(mealTest, beerTest, hopsTest);
    TestResultsGrouper grouper = new TestResultsGrouper(tests);

    Capture<Boolean> isMealDependenciesPass = newCapture();
    Capture<Boolean> isBeerDependenciesPass = newCapture();
    Capture<Boolean> isHopsDependenciesPass = newCapture();

    TestResults mealTestResults = failTestsAndCapture(isMealDependenciesPass);
    TestResults beerTestResults = failTestsAndCapture(isBeerDependenciesPass);
    TestResults hopsTestResults = failTestsAndCapture(isHopsDependenciesPass);

    assertGroupedTestResults(grouper.post(beerTest, beerTestResults));
    assertGroupedTestResults(grouper.post(mealTest, mealTestResults));
    assertGroupedTestResults(grouper.post(hopsTest, hopsTestResults), mealTest, beerTest, hopsTest);

    assertFalse("Meal should have a failed dependency (hops)", isMealDependenciesPass.getValue());
    assertFalse("Beer should have a failed dependency (hops)", isBeerDependenciesPass.getValue());
    assertTrue("Hops has no failed dependencies", isHopsDependenciesPass.getValue());
  }

  @Test
  public void shouldOnlySeeEachTestResultsOnceRegardlessOfTheOrderInWhichTestsFinish() {
    List<TestRule> tests = ImmutableList.of(mealTest, beerTest, hopsTest);
    List<List<TestRule>> permutations = allPermutations(tests);

    for (List<TestRule> permutation : permutations) {
      TestResultsGrouper grouper = new TestResultsGrouper(tests);
      try {
        for (TestRule testRule : permutation) {
          grouper.post(testRule, passTests());
        }
      } catch (IllegalStateException e) {
        String testNamesList = formatMockTestRules(permutation);
        String message = String.format("When testing %s: %s", testNamesList, e.getMessage());
        throw new AssertionError(message, e);
      }
    }
  }

  private String formatMockTestRules(List<TestRule> testRules) {
    List<String> testNames = Lists.newArrayList();
    for (TestRule testRule : testRules) {
      testNames.add(testRule.getBuildTarget().getFullyQualifiedName());
    }
    return "{" + Joiner.on(", ").join(testNames) + "}";
  }

  private List<List<TestRule>> allPermutations(Collection<TestRule> set) {
    if (set.isEmpty()) {
      List<TestRule> empty = ImmutableList.of();
      return ImmutableList.of(empty);
    }

    ImmutableList.Builder<List<TestRule>> resultBuilder = new ImmutableList.Builder<>();
    for (TestRule head : set) {
      Set<TestRule> remainder = Sets.newHashSet(set);
      remainder.remove(head);
      for (List<TestRule> tail : allPermutations(remainder)) {
        ImmutableList.Builder<TestRule> permutationBuilder = new ImmutableList.Builder<>();
        ImmutableList<TestRule> permutation = permutationBuilder.add(head).addAll(tail).build();
        resultBuilder.add(permutation);
      }
    }
    return resultBuilder.build();
  }

  private void assertGroupedTestResults(
      Map<TestRule, TestResults> actualFlushedTestResults,
      TestRule... expectedFlushedTestRules) {
    Set<TestRule> actual = actualFlushedTestResults.keySet();
    Set<TestRule> expected = ImmutableSet.copyOf(expectedFlushedTestRules);
    assertEquals(expected, actual);
  }
}
