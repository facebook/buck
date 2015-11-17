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
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.cache.FileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
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
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    BuildRuleResolver ruleResolver1 = new BuildRuleResolver();
    BuildRuleResolver ruleResolver2 = new BuildRuleResolver();

    // Create a dependent build rule, //src/com/facebook/buck/cli:common.
    JavaLibraryBuilder builder = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"));
    BuildRule commonJavaLibrary = builder.build(ruleResolver1);
    builder.build(ruleResolver2);

    // Create a java_library() rule with no deps.
    Path mainSrc = Paths.get("src/com/facebook/buck/cli/Main.java");
    filesystem.mkdirs(mainSrc.getParent());
    filesystem.writeContentsToPath("hello", mainSrc);
    JavaLibraryBuilder javaLibraryBuilder = JavaLibraryBuilder
        .createBuilder(BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:cli"))
        .addSrc(mainSrc);
    BuildRule libraryNoCommon = javaLibraryBuilder.build(ruleResolver1, filesystem);

    // Create the same java_library() rule, but with a dep on //src/com/facebook/buck/cli:common.
    javaLibraryBuilder.addDep(commonJavaLibrary.getBuildTarget());
    BuildRule libraryWithCommon = javaLibraryBuilder.build(ruleResolver2, filesystem);

    // Assert that the RuleKeys are distinct.
    RuleKey r1 = libraryNoCommon.getRuleKey();
    RuleKey r2 = libraryWithCommon.getRuleKey();
    assertThat("Rule keys should be distinct because the deps of the rules are different.",
        r1,
        not(equalTo(r2)));
  }

  @Test
  public void ensureSimpleValuesCorrectRuleKeyChangesMade() {
    RuleKey reflective = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .setReflectively("long", 42L)
        .setReflectively("boolean", true)
        .setReflectively("path", Paths.get("location", "of", "the", "rebel", "plans"))
        .build();

    RuleKey manual = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .setReflectively("long", 42L)
        .setReflectively("boolean", true)
        .setReflectively("path", Paths.get("location", "of", "the", "rebel", "plans"))
        .build();

    assertEquals(manual, reflective);
  }

  @Test
  public void ensureTwoListsOfSameRuleKeyAppendablesHaveSameRuleKey() {
    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableList =
        ImmutableList.of(
            new TestRuleKeyAppendable("foo"),
            new TestRuleKeyAppendable("bar"));

    RuleKey ruleKeyPairA = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableList)
            .build();

    RuleKey ruleKeyPairB = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableList)
            .build();

    assertEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureTwoListsOfDifferentRuleKeyAppendablesHaveDifferentRuleKeys() {
    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableListA =
        ImmutableList.of(
            new TestRuleKeyAppendable("foo"),
            new TestRuleKeyAppendable("bar"));

    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableListB =
        ImmutableList.of(
            new TestRuleKeyAppendable("bar"),
            new TestRuleKeyAppendable("foo"));

    RuleKey ruleKeyPairA = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableListA)
            .build();

    RuleKey ruleKeyPairB = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableListB)
            .build();

    assertNotEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureTwoMapsOfSameRuleKeyAppendablesHaveSameRuleKey() {
    ImmutableMap<String, TestRuleKeyAppendable> ruleKeyAppendableMap =
        ImmutableMap.of(
            "foo", new TestRuleKeyAppendable("foo"),
            "bar", new TestRuleKeyAppendable("bar"));

    RuleKey ruleKeyPairA = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMap)
            .build();

    RuleKey ruleKeyPairB = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMap)
            .build();

    assertEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureTwoMapsOfDifferentRuleKeyAppendablesHaveDifferentRuleKeys() {
    ImmutableMap<String, TestRuleKeyAppendable> ruleKeyAppendableMapA =
        ImmutableMap.of(
            "foo", new TestRuleKeyAppendable("foo"),
            "bar", new TestRuleKeyAppendable("bar"));

    ImmutableMap<String, TestRuleKeyAppendable> ruleKeyAppendableMapB =
        ImmutableMap.of(
            "bar", new TestRuleKeyAppendable("bar"),
            "foo", new TestRuleKeyAppendable("foo"));

    RuleKey ruleKeyPairA = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMapA)
            .build();

    RuleKey ruleKeyPairB = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMapB)
            .build();

    assertNotEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureListsAreHandledProperly() {
    ImmutableList<SourceRoot> sourceroots = ImmutableList.of(new SourceRoot("cake"));
    ImmutableList<String> strings = ImmutableList.of("one", "two");

    RuleKey reflective = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .setReflectively("sourceroot", sourceroots)
        .setReflectively("strings", strings)
        .build();

    RuleKey manual = createEmptyRuleKey(
        new SourcePathResolver(new BuildRuleResolver()))
        .setReflectively("sourceroot", sourceroots)
        .setReflectively("strings", strings)
        .build();

    assertEquals(manual, reflective);
  }

  @Test
  public void testRuleKeyEqualsAndHashCodeMethods() {
    RuleKey keyPair1 =
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("something", "foo")
            .build();
    RuleKey keyPair2 =
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("something", "foo")
            .build();
    RuleKey keyPair3 =
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
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    // Changing the name of a named source path should change the hash...
    assertNotEquals(
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("key", new PathSourcePath(projectFilesystem, Paths.get("something")))
            .build(),
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively(
                "key",
                new PathSourcePath(projectFilesystem, Paths.get("something", "else")))
            .build());

    // ... as should changing the key
    assertNotEquals(
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("key", new PathSourcePath(projectFilesystem, Paths.get("something")))
            .build(),
        createEmptyRuleKey(
            new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively(
                "different-key",
                new PathSourcePath(projectFilesystem, Paths.get("something")))
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

    // Verify that two BuildTargetSourcePaths with the same rule and path are equal.
    assertEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("location")))
            .build());

    // Verify that just changing the path of the build rule changes the rule key.
    assertNotEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("different")))
            .build());

    // Verify that just changing the build rule rule key changes the calculated rule key.
    assertNotEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake2.getBuildTarget(),
                    Paths.get("location")))
            .build());

    // Verify that just changing the key changes the calculated rule key.
    assertNotEquals(
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("location")))
            .build(),
        createEmptyRuleKey(
            pathResolver)
            .setReflectively(
                "different-key",
                new BuildTargetSourcePath(
                    fake1.getBuildTarget(),
                    Paths.get("location")))
            .build());
  }

  @Test
  public void canAddMapsToRuleKeys() {
    ImmutableMap<String, ?> map = ImmutableMap.of(
        "path",
        Paths.get("some/path"),
        "boolean",
        true);

    RuleKey key =
        createEmptyRuleKey(new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("map", map)
            .build();

    assertNotNull(key);
  }

  @Test
  public void keysOfMapsAddedToRuleKeysDoNotNeedToBeStrings() {
    ImmutableMap<?, ?> map = ImmutableMap.of(
        Paths.get("some/path"), "woohoo!",
        42L, "life, the universe and everything");

    RuleKey key =
        createEmptyRuleKey(new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("map", map)
            .build();

    assertNotNull(key);
  }

  @Test
  public void canAddRuleKeyAppendable() {
    RuleKey key =
        createEmptyRuleKey(new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("rule_key_appendable", new TestRuleKeyAppendable("foo"))
            .build();
    assertNotNull(key);
  }

  @Test
  public void canAddListOfRuleKeyAppendable() {
    ImmutableList<TestRuleKeyAppendable> list = ImmutableList.of(
        new TestRuleKeyAppendable("foo"),
        new TestRuleKeyAppendable("bar"));
    RuleKey key =
        createEmptyRuleKey(new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("list", list)
            .build();
    assertNotNull(key);
  }

  @Test
  public void canAddMapOfRuleKeyAppendable() {
    ImmutableMap<String, TestRuleKeyAppendable> map = ImmutableMap.of(
        "foo", new TestRuleKeyAppendable("foo"),
        "bar", new TestRuleKeyAppendable("bar"));
    RuleKey key =
        createEmptyRuleKey(new SourcePathResolver(new BuildRuleResolver()))
            .setReflectively("map", map)
            .build();
    assertNotNull(key);
  }

  @Test
  public void changingRuleKeyFieldChangesKeyWhenClassImplementsAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule buildRule1 = new TestRuleKeyAppendableBuildRule(
        params,
        pathResolver,
        "foo",
        "bar");
    BuildRule buildRule2 = new TestRuleKeyAppendableBuildRule(
        params,
        pathResolver,
        "foo",
        "xyzzy");

    RuleKey ruleKey1 = buildRule1.getRuleKey();
    RuleKey ruleKey2 = buildRule2.getRuleKey();

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void changingRuleKeyFieldOfDepChangesKeyWhenClassImplementsAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildRule buildRule1 = new TestRuleKeyAppendableBuildRule(
        params,
        pathResolver,
        "foo",
        "bar");
    BuildRule buildRule2 = new TestRuleKeyAppendableBuildRule(
        params,
        pathResolver,
        "foo",
        "xyzzy");

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//cheese:milk");

    BuildRuleParams parentParams1 = new FakeBuildRuleParamsBuilder(parentTarget)
        .setDeclaredDeps(ImmutableSortedSet.of(buildRule1))
        .build();
    BuildRule parentRule1 = new NoopBuildRule(parentParams1, pathResolver);
    BuildRuleParams parentParams2 = new FakeBuildRuleParamsBuilder(parentTarget)
        .setDeclaredDeps(ImmutableSortedSet.of(buildRule2))
        .build();
    BuildRule parentRule2 = new NoopBuildRule(parentParams2, pathResolver);

    RuleKey ruleKey1 = parentRule1.getRuleKey();
    RuleKey ruleKey2 = parentRule2.getRuleKey();

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void subclassWithNoopSetter() {
    class NoopSetterRuleKeyBuilder extends RuleKeyBuilder {

      public NoopSetterRuleKeyBuilder(SourcePathResolver pathResolver, FileHashCache hashCache) {
        super(pathResolver, hashCache);
      }

      @Override
      protected RuleKeyBuilder setSourcePath(SourcePath sourcePath) {
        return this;
      }
    }

    SourcePathResolver pathResolver = new SourcePathResolver(new BuildRuleResolver());
    FileHashCache hashCache = new FakeFileHashCache(ImmutableMap.<Path, HashCode>of());

    RuleKey nullRuleKey = new NoopSetterRuleKeyBuilder(pathResolver, hashCache)
        .build();
    RuleKey noopRuleKey = new NoopSetterRuleKeyBuilder(pathResolver, hashCache)
        .setReflectively("key", new FakeSourcePath("value"))
        .build();

    assertThat(noopRuleKey, is(equalTo(nullRuleKey)));
  }

  @Test
  public void declaredDepsAndExtraDepsGenerateDifferentRuleKeys() {
    SourcePathResolver sourcePathResolver = new SourcePathResolver(new BuildRuleResolver());
    BuildTarget target = BuildTargetFactory.newInstance("//a:target");

    BuildTarget depTarget = BuildTargetFactory.newInstance("//some:dep");
    BuildRuleParams depParams = new FakeBuildRuleParamsBuilder(depTarget).build();
    NoopBuildRule dep = new NoopBuildRule(depParams, sourcePathResolver);

    BuildRuleParams paramsWithDeclaredDep = new FakeBuildRuleParamsBuilder(target)
        .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    NoopBuildRule ruleWithDeclaredDep =
        new NoopBuildRule(paramsWithDeclaredDep, sourcePathResolver);

    BuildRuleParams paramsWithExtraDep = new FakeBuildRuleParamsBuilder(target)
        .setExtraDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    NoopBuildRule ruleWithExtraDep =
        new NoopBuildRule(paramsWithExtraDep, sourcePathResolver);

    BuildRuleParams paramsWithBothDeps = new FakeBuildRuleParamsBuilder(target)
        .setDeclaredDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .setExtraDeps(ImmutableSortedSet.<BuildRule>of(dep))
        .build();
    NoopBuildRule ruleWithBothDeps =
        new NoopBuildRule(paramsWithBothDeps, sourcePathResolver);

    assertNotEquals(ruleWithDeclaredDep.getRuleKey(), ruleWithExtraDep.getRuleKey());
    assertNotEquals(ruleWithDeclaredDep.getRuleKey(), ruleWithBothDeps.getRuleKey());
    assertNotEquals(ruleWithExtraDep.getRuleKey(), ruleWithBothDeps.getRuleKey());
  }

  private static class TestRuleKeyAppendable implements RuleKeyAppendable {
    private final String value;

    public TestRuleKeyAppendable(String value) {
      this.value = value;
    }

    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      return builder
          .setReflectively("value", value)
          .setReflectively("foo", "foo")
          .setReflectively("bar", "bar");
    }
  }

  private static class TestRuleKeyAppendableBuildRule extends NoopBuildRule
      implements RuleKeyAppendable {
    private final String foo;

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final String bar;

    public TestRuleKeyAppendableBuildRule(
        BuildRuleParams buildRuleParams,
        SourcePathResolver sourcePathResolver,
        String foo,
        String bar) {
      super(buildRuleParams, sourcePathResolver);
      this.foo = foo;
      this.bar = bar;
    }

    @Override
    public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
      return builder
          .setReflectively("foo", foo);
    }
  }

  private RuleKeyBuilder createEmptyRuleKey(SourcePathResolver resolver) {
    FileHashCache fileHashCache =
        new FileHashCache() {
          @Override
          public boolean willGet(Path path) {
            return true;
          }

          @Override
          public void invalidate(Path path) {
          }

          @Override
          public void invalidateAll() {
          }

          @Override
          public HashCode get(Path path) {
            return HashCode.fromString("deadbeef");
          }
        };
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:example");
    BuildRule buildRule = new FakeBuildRule(buildTarget, resolver);
    return new DefaultRuleKeyBuilderFactory(fileHashCache, resolver).newInstance(buildRule);
  }
}
