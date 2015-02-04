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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.util.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
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
    javaLibraryBuilder.addDep(commonJavaLibrary.getBuildTarget());
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
    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .setReflectively("long", 42L)
        .setReflectively("boolean", true)
        .setReflectively("path", Paths.get("location", "of", "the", "rebel", "plans"))
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .set("long", 42L)
        .set("boolean", true)
        .setInput("path", Paths.get("location", "of", "the", "rebel", "plans"))
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureListsAreHandledProperly() {
    ImmutableList<SourceRoot> sourceroots = ImmutableList.of(new SourceRoot("cake"));
    ImmutableList<String> strings = ImmutableList.of("one", "two");

    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .setReflectively("sourceroot", sourceroots)
        .setReflectively("strings", strings)
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .set("sourceroot", sourceroots)
        .set("strings", strings)
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void ensureSetsAreHandledProperly() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    BuildTarget target = BuildTargetFactory.newInstance("//foo/bar:baz");
    FakeBuildRule rule = new FakeBuildRule(
        ImmutableBuildRuleType.of("example"),
        target,
        pathResolver);
    rule.setRuleKey(RuleKey.TO_RULE_KEY.apply("cafebabe"));
    rule.setOutputFile("cheese.txt");
    resolver.addToIndex(rule);

    ImmutableSortedSet<SourcePath> sourcePaths = ImmutableSortedSet.<SourcePath>of(
        new BuildTargetSourcePath(rule.getBuildTarget()),
        new TestSourcePath("alpha/beta"));
    ImmutableSet<String> strings = ImmutableSet.of("one", "two");

    RuleKey.Builder.RuleKeyPair reflective = createEmptyRuleKey(pathResolver)
        .setReflectively("sourcePaths", sourcePaths)
        .setReflectively("strings", strings)
        .build();

    RuleKey.Builder.RuleKeyPair manual = createEmptyRuleKey(pathResolver)
        .setSourcePaths("sourcePaths", sourcePaths)
        .set("strings", strings)
        .build();

    assertEquals(manual.getTotalRuleKey(), reflective.getTotalRuleKey());
  }

  @Test
  public void testRuleKeyPairEqualsAndHashCodeMethods() {
    RuleKey.Builder.RuleKeyPair keyPair1 =
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("something", "foo")
            .build();
    RuleKey.Builder.RuleKeyPair keyPair2 =
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("something", "foo")
            .build();
    RuleKey.Builder.RuleKeyPair keyPair3 =
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("something", "bar")
            .build();
    assertEquals(keyPair1, keyPair2);
    assertEquals(keyPair1.hashCode(), keyPair2.hashCode());
    assertNotEquals(keyPair1, keyPair3);
    assertNotEquals(keyPair1.hashCode(), keyPair3.hashCode());
    assertNotEquals(keyPair2, keyPair3);
    assertNotEquals(keyPair2.hashCode(), keyPair3.hashCode());
  }

  @Test
  public void setInputPathSourcePath() {

    // Just changing the name of a named source path shouldn't change the hash.
    assertEquals(
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("key", new PathSourcePath(Paths.get("something")))
            .build(),
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("key", new PathSourcePath(Paths.get("something", "else")))
            .build());

    // But changing the key should...
    assertNotEquals(
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("key", new PathSourcePath(Paths.get("something")))
            .build(),
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("different-key", new PathSourcePath(Paths.get("something")))
            .build());
  }

  @Test
  public void setInputBuildTargetSourcePath() {
    BuildRuleResolver resolver = new BuildRuleResolver();
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    FakeBuildRule fake1 = new FakeBuildRule("//:fake1", pathResolver);
    fake1.setRuleKey(RuleKey.TO_RULE_KEY.apply("deadbeef"));
    FakeBuildRule fake2 = new FakeBuildRule("//:fake2", pathResolver);
    fake2.setRuleKey(RuleKey.TO_RULE_KEY.apply("feeddeed"));
    resolver.addToIndex(fake1);
    resolver.addToIndex(fake2);

    // Verify that just changing the path of the build rule doesn't affect the rule key.
    assertEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(fake1.getBuildTarget(), Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(fake1.getBuildTarget(), Paths.get("different")))
            .build());

    // Verify that just changing the build rule rule key changes the calculated rule key.
    assertNotEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(fake1.getBuildTarget(), Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(fake2.getBuildTarget(), Paths.get("location")))
            .build());

    // Verify that just changing the key changes the calculated rule key.
    assertNotEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(fake1.getBuildTarget(), Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "different-key",
                new BuildTargetSourcePath(fake1.getBuildTarget(), Paths.get("location")))
            .build());
  }

  private RuleKey.Builder createEmptyRuleKey(SourcePathResolver resolver) {
    return RuleKey.builder(
        BuildTargetFactory.newInstance("//some:example"),
        ImmutableBuildRuleType.of("example"),
        resolver,
        ImmutableSortedSet.<BuildRule>of(),
        ImmutableSortedSet.<BuildRule>of(), new FileHashCache() {
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
