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

package com.facebook.buck.rules;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.java.FakeJavaLibrary;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.List;

public class AbstractDependencyVisitorTest {

  private static final String BUILD_RULE_BASE_NAME = "//java";

  @Test
  public void testIsBreadthFirst() {
    // The dependency graph of build rules being built up is as follows:
    //
    //                       J
    //                /    /   \     \
    //              F    G       H     I
    //            /   \        /   \     \
    //          E       D    C       B     A
    //
    BuildRule ruleA = createRule("A");
    BuildRule ruleB = createRule("B");
    BuildRule ruleC = createRule("C");
    BuildRule ruleD = createRule("D");
    BuildRule ruleE = createRule("E");

    BuildRule ruleF = createRule("F", ruleE, ruleD);
    BuildRule ruleG = createRule("G");
    BuildRule ruleH = createRule("H", ruleC, ruleB);
    BuildRule ruleI = createRule("I", ruleA);

    BuildRule initialRule = createRule("J", ruleF, ruleG, ruleH, ruleI);

    final List<BuildRule> buildRuleTraversalOrder = Lists.newArrayList();
    new AbstractDependencyVisitor(initialRule) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        buildRuleTraversalOrder.add(rule);
        return rule.getDeps();
      }
    }.start();

    assertEquals(
        "Dependencies should be explored depth-first, using lexicographic ordering to break ties",
        ImmutableList.of(initialRule,
            ruleF,
            ruleG,
            ruleH,
            ruleI,
            ruleD,
            ruleE,
            ruleB,
            ruleC,
            ruleA),
        buildRuleTraversalOrder);
  }

  @Test
  public void testSubsetWorks() {
    // The dependency graph of build rules being built up is as follows:
    //
    //                       10
    //                /    /   \     \
    //              6    7       8     9
    //            /   \        /   \     \
    //          5       4    3       2     1
    //
    BuildRule rule1 = createRule("1");
    BuildRule rule2 = createRule("2");
    BuildRule rule3 = createRule("3");
    BuildRule rule4 = createRule("4");
    BuildRule rule5 = createRule("5");

    BuildRule rule6 = createRule("6", rule5, rule4);
    BuildRule rule7 = createRule("7");
    BuildRule rule8 = createRule("8", rule3, rule2);
    BuildRule rule9 = createRule("9", rule1);

    BuildRule initialRule = createRule("10", rule6, rule7, rule8, rule9);

    final List<BuildRule> buildRuleTraversalOrder = Lists.newArrayList();

    // This visitor only visits dependencies whose node names are even numbers.
    new AbstractDependencyVisitor(initialRule) {
      @Override
      public ImmutableSet<BuildRule> visit(BuildRule rule) {
        buildRuleTraversalOrder.add(rule);
        return ImmutableSet.copyOf(Iterables.filter(rule.getDeps(), new Predicate<BuildRule>() {
          @Override
          public boolean apply(BuildRule input) {
            return Integer.parseInt(input.getBuildTarget().getShortName()) % 2 == 0;
          }
        }));
      }
    }.start();

    assertEquals(
        "Dependencies should be explored depth-first, only containing rules whose rule name is " +
            "an even number",
        ImmutableList.of(initialRule,
            rule6,
            rule8,
            rule4,
            rule2),
        buildRuleTraversalOrder);
  }

  private FakeJavaLibrary createRule(String name, BuildRule... deps) {
    ImmutableSet<BuildTargetPattern> visibilityPatterns = ImmutableSet.of();
    FakeJavaLibrary rule = new FakeJavaLibrary(
        JavaLibraryDescription.TYPE,
        new BuildTarget(BUILD_RULE_BASE_NAME, name),
        ImmutableSortedSet.copyOf(deps),
        visibilityPatterns);
    return rule;
  }
}
