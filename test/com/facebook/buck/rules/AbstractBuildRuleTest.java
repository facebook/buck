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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.model.SingletonBuildTargetPattern;
import com.facebook.buck.model.SubdirectoryBuildTargetPattern;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import org.junit.Test;

import java.nio.file.Path;
import java.util.Comparator;

import javax.annotation.Nullable;

public class AbstractBuildRuleTest {

  private static final BuildTarget orcaTarget =
      new BuildTarget("//src/com/facebook/orca", "orca");
  private static final BuildTarget publicTarget =
      new BuildTarget("//src/com/facebook/for", "everyone");
  private static final BuildTarget nonPublicTarget1 =
      new BuildTarget("//src/com/facebook/something1", "nonPublic");
  private static final BuildTarget nonPublicTarget2 =
      new BuildTarget("//src/com/facebook/something2", "nonPublic");

  private static final ImmutableSet<BuildRule> noDeps = ImmutableSet.of();
  private static final ImmutableSet<BuildTargetPattern> noVisibilityPatterns = ImmutableSet.of();

  @Test
  public void testVisibilityPublic() {
    BuildRule publicBuildRule = createRule(publicTarget, noDeps,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    AbstractBuildRule orcaRule = createRule(orcaTarget, ImmutableSet.of(publicBuildRule),
        noVisibilityPatterns);
    assertTrue(publicBuildRule.isVisibleTo(orcaTarget));
    assertFalse(orcaRule.isVisibleTo(publicTarget));
  }

  @Test
  public void testVisibilityNonPublic() {
    BuildRule nonPublicBuildRule1 = createRule(nonPublicTarget1, noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    BuildRule nonPublicBuildRule2 = createRule(nonPublicTarget2, noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    AbstractBuildRule orcaRule = createRule(orcaTarget,
        ImmutableSet.of(nonPublicBuildRule1, nonPublicBuildRule2),
        noVisibilityPatterns);

    assertTrue(shouldBeVisibleMessage(nonPublicBuildRule1, orcaTarget),
        nonPublicBuildRule1.isVisibleTo(orcaTarget));
    assertTrue(shouldBeVisibleMessage(nonPublicBuildRule2, orcaTarget),
        nonPublicBuildRule2.isVisibleTo(orcaTarget));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget1));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget2));

    BuildRule publicBuildRule = createRule(publicTarget,
        noDeps,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    assertTrue(publicBuildRule.isVisibleTo(nonPublicTarget1));
    assertFalse(nonPublicBuildRule1.isVisibleTo(publicTarget));
  }

  @Test
  public void testVisibilityNonPublicFailure() {
    BuildRule nonPublicBuildRule1 = createRule(nonPublicTarget1,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    try {
     createRule(publicTarget,
         ImmutableSet.of(nonPublicBuildRule1),
         ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
      fail("createRule() should throw an exception");
    } catch (RuntimeException e) {
      assertEquals(
          String.format("%s depends on %s, which is not visible",
              publicTarget,
              nonPublicBuildRule1),
          e.getMessage());
    }
  }

  @Test
  public void testVisibilityMix() {
    BuildRule nonPublicBuildRule1 = createRule(nonPublicTarget1,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    BuildRule nonPublicBuildRule2 = createRule(nonPublicTarget2,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    BuildRule publicBuildRule = createRule(publicTarget,
        noDeps,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    AbstractBuildRule orcaRule = createRule(orcaTarget,
        ImmutableSet.of(publicBuildRule, nonPublicBuildRule1, nonPublicBuildRule2),
        noVisibilityPatterns);

    assertTrue(shouldBeVisibleMessage(nonPublicBuildRule1, orcaTarget),
        nonPublicBuildRule1.isVisibleTo(orcaTarget));
    assertTrue(shouldBeVisibleMessage(nonPublicBuildRule2, orcaTarget),
        nonPublicBuildRule2.isVisibleTo(orcaTarget));
    assertTrue(publicBuildRule.isVisibleTo(orcaTarget));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget1));
    assertFalse(orcaRule.isVisibleTo(nonPublicTarget2));
    assertFalse(orcaRule.isVisibleTo(publicTarget));
  }

  @Test
  public void testVisibilityMixFailure() {
    BuildRule nonPublicBuildRule1 = createRule(nonPublicTarget1,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern(orcaTarget.getFullyQualifiedName())));
    BuildRule nonPublicBuildRule2 = createRule(nonPublicTarget2,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SingletonBuildTargetPattern("//some/other:target")));
    BuildRule publicBuildRule = createRule(publicTarget,
        noDeps,
        ImmutableSet.of(BuildTargetPattern.MATCH_ALL));
    try {
      createRule(orcaTarget,
          ImmutableSet.of(publicBuildRule, nonPublicBuildRule1, nonPublicBuildRule2),
          noVisibilityPatterns);
      fail("createRule() should throw an exception");
    } catch (RuntimeException e) {
      assertEquals(
          String.format("%s depends on %s, which is not visible", orcaTarget, nonPublicBuildRule2),
          e.getMessage());
    }
  }

  @Test
  public void testVisibilityForDirectory() {
    BuildTarget libTarget = new BuildTarget("//lib", "lib");
    BuildTarget targetInSpecifiedDirectory = new BuildTarget("//src/com/facebook", "test");
    BuildTarget targetUnderSpecifiedDirectory = new BuildTarget("//src/com/facebook/buck", "test");
    BuildTarget targetInOtherDirectory = new BuildTarget("//src/com/instagram", "test");
    BuildTarget targetInParentDirectory = new BuildTarget("//", "test");

    // Build rule that visible to targets in or under directory src/come/facebook
    BuildRule directoryBuildRule = createRule(libTarget,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(
            new SubdirectoryBuildTargetPattern("src/com/facebook/")));
    assertTrue(directoryBuildRule.isVisibleTo(targetInSpecifiedDirectory));
    assertTrue(directoryBuildRule.isVisibleTo(targetUnderSpecifiedDirectory));
    assertFalse(directoryBuildRule.isVisibleTo(targetInOtherDirectory));
    assertFalse(directoryBuildRule.isVisibleTo(targetInParentDirectory));

    // Build rule that visible to all targets, equals to PUBLIC.
    BuildRule pubicBuildRule = createRule(libTarget,
        noDeps,
        ImmutableSet.<BuildTargetPattern>of(new SubdirectoryBuildTargetPattern("")));
    assertTrue(pubicBuildRule.isVisibleTo(targetInSpecifiedDirectory));
    assertTrue(pubicBuildRule.isVisibleTo(targetUnderSpecifiedDirectory));
    assertTrue(pubicBuildRule.isVisibleTo(targetInOtherDirectory));
    assertTrue(pubicBuildRule.isVisibleTo(targetInParentDirectory));
  }

  private String shouldBeVisibleMessage(BuildRule rule, BuildTarget target) {
    return String.format(
        "%1$s should be visible to %2$s because the visibility list of %1$s contains %2$s",
        rule,
        target);
  }

  private static AbstractBuildRule createRule(BuildTarget buildTarget,
      ImmutableSet<BuildRule> deps,
      ImmutableSet<BuildTargetPattern> visibilityPatterns) {
    Comparator<BuildRule> comparator = RetainOrderComparator.createComparator(deps);
    ImmutableSortedSet<BuildRule> sortedDeps = ImmutableSortedSet.copyOf(comparator, deps);

    BuildRuleParams buildRuleParams = new FakeBuildRuleParamsBuilder(buildTarget)
        .setDeps(sortedDeps)
        .setVisibility(visibilityPatterns)
        .build();
    return new AbstractBuildRule(buildRuleParams, null) {
      @Override
      public BuildRuleType getType() {
        throw new IllegalStateException("This method should not be called");
      }

      @Nullable
      @Override
      public Buildable getBuildable() {
        return null;
      }

      @Override
      public final Iterable<Path> getInputs() {
        return ImmutableList.of();
      }

      @Override
      public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
        throw new IllegalStateException("This method should not be called");
      }
    };
  }
}
