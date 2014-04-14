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

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.FileHashCache;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashCode;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class RuleKeyTest {

  @Test
  public void testRuleKeyFromHashString() {
    RuleKey ruleKey = new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208");
    assertEquals("19d2558a6bd3a34fb3f95412de9da27ed32fe208", ruleKey.toString());
  }

  /**
   * Ensure that build rules with the same inputs but different deps have unique RuleKeys.
   */
  @Test
  public void testRuleKeyDependsOnDeps() throws IOException {
    BuildRuleResolver ruleResolver1 = new BuildRuleResolver();
    BuildRuleResolver ruleResolver2 = new BuildRuleResolver();

    // Create a dependent build rule, //src/com/facebook/buck/cli:common.
    JavaLibraryBuilder builder = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"));
    BuildRule commonJavaLibrary = builder.build(ruleResolver1);
    builder.build(ruleResolver2);

    // Create a java_library() rule with no deps.
    JavaLibraryBuilder javaLibraryBuilder = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:cli"))
            // The source file must be an existing file or else RuleKey.Builder.setVal(File) will
            // throw an IOException, which is caught and then results in the rule being flagged as
            // "not idempotent", which screws up this test.
            // TODO(mbolin): Update RuleKey.Builder.setVal(File) to use a ProjectFilesystem so that
            // file access can be mocked appropriately during a unit test.
        .addSrc(Paths.get("src/com/facebook/buck/cli/Main.java"));
    BuildRule libraryNoCommon = javaLibraryBuilder.build(ruleResolver1);

    // Create the same java_library() rule, but with a dep on //src/com/facebook/buck/cli:common.
    javaLibraryBuilder.addDep(commonJavaLibrary);
    BuildRule libraryWithCommon = javaLibraryBuilder.build(ruleResolver2);

    // Assert that the RuleKeys are distinct.
    RuleKey r1 = libraryNoCommon.getRuleKey();
    RuleKey r2 = libraryWithCommon.getRuleKey();
    assertThat("Rule keys should be distinct because the deps of the rules are different.",
        r1,
        not(equalTo(r2)));
  }

  @Test
  public void ensureSimpleValuesCorrectRuleKeyChangesMade() {
    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey()
        .setReflectively("long", 42L)
        .setReflectively("boolean", true)
        .setReflectively("path", Paths.get("location", "of", "the", "rebel", "plans"))
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey()
        .set("long", 42L)
        .set("boolean", true)
        .setInput("path", Paths.get("location", "of", "the", "rebel", "plans"))
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureOptionalValuesAreSetAsStringsOrNulls() {
    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey()
        .setReflectively("food", Optional.of("cheese"))
        .setReflectively("empty", Optional.<String>absent())
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey()
        .set("food", Optional.of("cheese"))
        .set("empty", Optional.<String>absent())
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureListsAreHandledProperly() {
    ImmutableList<SourceRoot> sourceroots = ImmutableList.of(new SourceRoot("cake"));
    ImmutableList<String> strings = ImmutableList.of("one", "two");

    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey()
        .setReflectively("sourceroot", sourceroots)
        .setReflectively("strings", strings)
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey()
        .set("sourceroot", sourceroots)
        .set("strings", strings)
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureListsDefaultToSettingStringValues() {
    ImmutableList<Label> labels = ImmutableList.of(new Label("one"), new Label("two"));

    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey()
        .setReflectively("labels", labels)
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey()
        .set("labels", Lists.transform(labels, Functions.toStringFunction()))
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureSetsAreHandledProperly() {
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(new BuildRuleType("example"), target);
    rule.setRuleKey(RuleKey.TO_RULE_KEY.apply("cafebabe"));
    rule.setOutputFile("cheese.txt");

    ImmutableSortedSet<SourcePath> sourcePaths = ImmutableSortedSet.<SourcePath>of(
        new BuildRuleSourcePath(rule),
        new TestSourcePath("alpha/beta")
    );
    ImmutableSet<String> strings = ImmutableSet.of("one", "two");

    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey()
        .setReflectively("sourcePaths", sourcePaths)
        .setReflectively("strings", strings)
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey()
        .setSourcePaths("sourcePaths", sourcePaths)
        .set("strings", strings)
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureSetsDefaultToSettingStringValues() {
    ImmutableSortedSet<Label> labels = ImmutableSortedSet.of(new Label("one"), new Label("two"));
    ImmutableSortedSet<String> stringLabels = ImmutableSortedSet.copyOf(
        Iterables.transform(
            labels,
            Functions.toStringFunction()));

    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey()
        .setReflectively("labels", labels)
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey()
        .set("labels", stringLabels)
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  private RuleKey.Builder createEmptyRuleKey() {
    return RuleKey.builder(
        BuildTargetFactory.newInstance("//some:example"),
        new BuildRuleType("example"),
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSortedSet.<BuildRule>of(),
        new FileHashCache() {
          @Override
          public boolean contains(Path path) {
            return true;
          }

          @Override
          public HashCode get(Path path) {
            return HashCode.fromString("deadbeef");
          }
        });
  }
}
