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

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyBuilder;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyResult;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.cache.DefaultFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.NullFileHashCache;
import com.facebook.buck.util.cache.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class RuleKeyTest {

  @Test
  public void testRuleKeyFromHashString() {
    RuleKey ruleKey = new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208");
    assertEquals("19d2558a6bd3a34fb3f95412de9da27ed32fe208", ruleKey.toString());
  }

  @Test(expected = HumanReadableException.class)
  public void shouldNotAllowPathsInRuleKeysWhenSetReflectively() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKeyBuilder<HashCode> builder = createBuilder(resolver, ruleFinder);

    builder.setReflectively("path", Paths.get("some/path"));
  }

  /** Ensure that build rules with the same inputs but different deps have unique RuleKeys. */
  @Test
  public void testRuleKeyDependsOnDeps() throws Exception {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(DefaultFileHashCache.createDefaultFileHashCache(filesystem)));
    BuildRuleResolver ruleResolver1 =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    BuildRuleResolver ruleResolver2 =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder1 = new SourcePathRuleFinder(ruleResolver1);
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, new SourcePathResolver(ruleFinder1), ruleFinder1);
    SourcePathRuleFinder ruleFinder2 = new SourcePathRuleFinder(ruleResolver2);
    DefaultRuleKeyFactory ruleKeyFactory2 =
        new DefaultRuleKeyFactory(0, hashCache, new SourcePathResolver(ruleFinder2), ruleFinder2);

    // Create a dependent build rule, //src/com/facebook/buck/cli:common.
    JavaLibraryBuilder builder =
        JavaLibraryBuilder.createBuilder(
            BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"));
    BuildRule commonJavaLibrary = builder.build(ruleResolver1);
    builder.build(ruleResolver2);

    // Create a java_library() rule with no deps.
    Path mainSrc = Paths.get("src/com/facebook/buck/cli/Main.java");
    filesystem.mkdirs(mainSrc.getParent());
    filesystem.writeContentsToPath("hello", mainSrc);
    JavaLibraryBuilder javaLibraryBuilder =
        JavaLibraryBuilder.createBuilder(
                BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:cli"))
            .addSrc(mainSrc);
    BuildRule libraryNoCommon = javaLibraryBuilder.build(ruleResolver1, filesystem);

    // Create the same java_library() rule, but with a dep on //src/com/facebook/buck/cli:common.
    javaLibraryBuilder.addDep(commonJavaLibrary.getBuildTarget());
    BuildRule libraryWithCommon = javaLibraryBuilder.build(ruleResolver2, filesystem);

    // Assert that the RuleKeys are distinct.
    RuleKey r1 = ruleKeyFactory.build(libraryNoCommon);
    RuleKey r2 = ruleKeyFactory2.build(libraryWithCommon);
    assertThat(
        "Rule keys should be distinct because the deps of the rules are different.",
        r1,
        not(equalTo(r2)));
  }

  @Test
  public void ensureSimpleValuesCorrectRuleKeyChangesMade() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey reflective =
        createBuilder(resolver, ruleFinder)
            .setReflectively("long", 42L)
            .setReflectively("boolean", true)
            .setReflectively("path", new FakeSourcePath("location/of/the/rebel/plans"))
            .build(RuleKey::new);

    RuleKey manual =
        createBuilder(resolver, ruleFinder)
            .setReflectively("long", 42L)
            .setReflectively("boolean", true)
            .setReflectively("path", new FakeSourcePath("location/of/the/rebel/plans"))
            .build(RuleKey::new);

    assertEquals(manual, reflective);
  }

  @Test
  public void ensureTwoListsOfSameRuleKeyAppendablesHaveSameRuleKey() {
    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableList =
        ImmutableList.of(new TestRuleKeyAppendable("foo"), new TestRuleKeyAppendable("bar"));

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey ruleKeyPairA =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableList)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableList)
            .build(RuleKey::new);

    assertEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureTwoListsOfDifferentRuleKeyAppendablesHaveDifferentRuleKeys() {
    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableListA =
        ImmutableList.of(new TestRuleKeyAppendable("foo"), new TestRuleKeyAppendable("bar"));

    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableListB =
        ImmutableList.of(new TestRuleKeyAppendable("bar"), new TestRuleKeyAppendable("foo"));

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey ruleKeyPairA =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableListA)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableListB)
            .build(RuleKey::new);

    assertNotEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureTwoMapsOfSameRuleKeyAppendablesHaveSameRuleKey() {
    ImmutableMap<String, TestRuleKeyAppendable> ruleKeyAppendableMap =
        ImmutableMap.of(
            "foo", new TestRuleKeyAppendable("foo"),
            "bar", new TestRuleKeyAppendable("bar"));

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey ruleKeyPairA =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMap)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMap)
            .build(RuleKey::new);

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

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey ruleKeyPairA =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMapA)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(resolver, ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMapB)
            .build(RuleKey::new);

    assertNotEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureListsAreHandledProperly() {
    ImmutableList<SourceRoot> sourceroots = ImmutableList.of(new SourceRoot("cake"));
    ImmutableList<String> strings = ImmutableList.of("one", "two");

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey reflective =
        createBuilder(resolver, ruleFinder)
            .setReflectively("sourceroot", sourceroots)
            .setReflectively("strings", strings)
            .build(RuleKey::new);

    RuleKey manual =
        createBuilder(resolver, ruleFinder)
            .setReflectively("sourceroot", sourceroots)
            .setReflectively("strings", strings)
            .build(RuleKey::new);

    assertEquals(manual, reflective);
  }

  @Test
  public void differentSeedsMakeDifferentKeys() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:example");
    BuildRule buildRule = new FakeBuildRule(buildTarget, resolver);

    RuleKey empty1 =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), resolver, ruleFinder)
            .build(buildRule);
    RuleKey empty2 =
        new DefaultRuleKeyFactory(0, new NullFileHashCache(), resolver, ruleFinder)
            .build(buildRule);
    RuleKey empty3 =
        new DefaultRuleKeyFactory(1, new NullFileHashCache(), resolver, ruleFinder)
            .build(buildRule);

    assertThat(empty1, is(equalTo(empty2)));
    assertThat(empty1, is(not(equalTo(empty3))));
  }

  @Test
  public void testRuleKeyEqualsAndHashCodeMethods() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey keyPair1 =
        createBuilder(resolver, ruleFinder).setReflectively("something", "foo").build(RuleKey::new);
    RuleKey keyPair2 =
        createBuilder(resolver, ruleFinder).setReflectively("something", "foo").build(RuleKey::new);
    RuleKey keyPair3 =
        createBuilder(resolver, ruleFinder).setReflectively("something", "bar").build(RuleKey::new);
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

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    // Changing the name of a named source path should change the hash...
    assertNotEquals(
        buildResult(
            createBuilder(resolver, ruleFinder)
                .setReflectively(
                    "key", new PathSourcePath(projectFilesystem, Paths.get("something")))),
        buildResult(
            createBuilder(resolver, ruleFinder)
                .setReflectively(
                    "key", new PathSourcePath(projectFilesystem, Paths.get("something", "else")))));

    // ... as should changing the key
    assertNotEquals(
        buildResult(
            createBuilder(resolver, ruleFinder)
                .setReflectively(
                    "key", new PathSourcePath(projectFilesystem, Paths.get("something")))),
        buildResult(
            createBuilder(resolver, ruleFinder)
                .setReflectively(
                    "different-key",
                    new PathSourcePath(projectFilesystem, Paths.get("something")))));
  }

  @Test
  public void setNonHashingSourcePathsWithDifferentRelativePaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    PathSourcePath sourcePathOne = new PathSourcePath(projectFilesystem, Paths.get("something"));
    PathSourcePath sourcePathTwo = new PathSourcePath(projectFilesystem, Paths.get("something2"));

    // Changing the relative path should change the rule key
    SourcePathRuleFinder ruleFinder1 =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathRuleFinder ruleFinder2 =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    assertNotEquals(
        buildResult(
            createBuilder(new SourcePathResolver(ruleFinder1), ruleFinder1)
                .setReflectively("key", new NonHashableSourcePathContainer(sourcePathOne))),
        buildResult(
            createBuilder(new SourcePathResolver(ruleFinder2), ruleFinder2)
                .setReflectively("key", new NonHashableSourcePathContainer(sourcePathTwo))));
  }

  @Test
  public void setInputBuildTargetSourcePath() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FakeBuildRule fake1 = new FakeBuildRule("//:fake1", pathResolver);
    FakeBuildRule fake2 = new FakeBuildRule("//:fake2", pathResolver);
    resolver.addToIndex(fake1);
    resolver.addToIndex(fake2);

    // Verify that two BuildTargetSourcePaths with the same rule and path are equal.
    assertEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("location")))));

    // Verify that just changing the path of the build rule changes the rule key.
    assertNotEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("different")))));

    // Verify that just changing the build rule rule key changes the calculated rule key.
    assertNotEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake2.getBuildTarget(), Paths.get("location")))));

    // Verify that just changing the key changes the calculated rule key.
    assertNotEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "different-key",
                    new ExplicitBuildTargetSourcePath(
                        fake1.getBuildTarget(), Paths.get("location")))));
  }

  @Test
  public void setInputArchiveMemberSourcePath() {
    BuildRuleResolver resolver =
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer());
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    final FakeBuildRule fakeBuildRule = new FakeBuildRule("//:fake", pathResolver);
    resolver.addToIndex(fakeBuildRule);

    ExplicitBuildTargetSourcePath archive1 =
        new ExplicitBuildTargetSourcePath(fakeBuildRule.getBuildTarget(), Paths.get("location"));
    PathSourcePath archive2 =
        new PathSourcePath(new FakeProjectFilesystem(), Paths.get("otherLocation"));

    // Verify that two ArchiveMemberSourcePaths with the same archive and path
    assertEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))));

    // Verify that just changing the archive changes the rule key
    assertNotEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive2, Paths.get("location")))));

    // Verify that just changing the member path changes the rule key
    assertNotEquals(
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))),
        buildResult(
            createBuilder(pathResolver, ruleFinder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("different")))));
  }

  @Test
  public void canAddMapsToRuleKeys() {
    ImmutableMap<String, ?> map =
        ImmutableMap.of("path", new FakeSourcePath("some/path"), "boolean", true);

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey key =
        createBuilder(resolver, ruleFinder).setReflectively("map", map).build(RuleKey::new);

    assertNotNull(key);
  }

  @Test
  public void keysOfMapsAddedToRuleKeysDoNotNeedToBeStrings() {
    ImmutableMap<?, ?> map =
        ImmutableMap.of(
            new FakeSourcePath("some/path"), "woohoo!", 42L, "life, the universe and everything");

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey key =
        createBuilder(resolver, ruleFinder).setReflectively("map", map).build(RuleKey::new);

    assertNotNull(key);
  }

  @Test
  public void canAddRuleKeyAppendable() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey key =
        createBuilder(resolver, ruleFinder)
            .setReflectively("rule_key_appendable", new TestRuleKeyAppendable("foo"))
            .build(RuleKey::new);
    assertNotNull(key);
  }

  @Test
  public void canAddListOfRuleKeyAppendable() {
    ImmutableList<TestRuleKeyAppendable> list =
        ImmutableList.of(new TestRuleKeyAppendable("foo"), new TestRuleKeyAppendable("bar"));
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey key =
        createBuilder(resolver, ruleFinder).setReflectively("list", list).build(RuleKey::new);
    assertNotNull(key);
  }

  @Test
  public void canAddMapOfRuleKeyAppendable() {
    ImmutableMap<String, TestRuleKeyAppendable> map =
        ImmutableMap.of(
            "foo", new TestRuleKeyAppendable("foo"),
            "bar", new TestRuleKeyAppendable("bar"));
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver resolver = new SourcePathResolver(ruleFinder);
    RuleKey key =
        createBuilder(resolver, ruleFinder).setReflectively("map", map).build(RuleKey::new);
    assertNotNull(key);
  }

  @Test
  public void changingRuleKeyFieldChangesKeyWhenClassImplementsAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(new FakeProjectFilesystem())));

    BuildRule buildRule1 = new TestRuleKeyAppendableBuildRule(params, "foo", "bar");
    BuildRule buildRule2 = new TestRuleKeyAppendableBuildRule(params, "foo", "xyzzy");

    RuleKey ruleKey1 =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(buildRule1);
    RuleKey ruleKey2 =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(buildRule2);

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void changingRuleKeyFieldOfDepChangesKeyWhenClassImplementsAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    BuildRuleParams params = new FakeBuildRuleParamsBuilder(target).build();
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(new FakeProjectFilesystem())));

    BuildRule buildRule1 = new TestRuleKeyAppendableBuildRule(params, "foo", "bar");
    BuildRule buildRule2 = new TestRuleKeyAppendableBuildRule(params, "foo", "xyzzy");

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//cheese:milk");

    BuildRuleParams parentParams1 =
        new FakeBuildRuleParamsBuilder(parentTarget)
            .setDeclaredDeps(ImmutableSortedSet.of(buildRule1))
            .build();
    BuildRule parentRule1 = new NoopBuildRule(parentParams1);
    BuildRuleParams parentParams2 =
        new FakeBuildRuleParamsBuilder(parentTarget)
            .setDeclaredDeps(ImmutableSortedSet.of(buildRule2))
            .build();
    BuildRule parentRule2 = new NoopBuildRule(parentParams2);

    RuleKey ruleKey1 =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(parentRule1);
    RuleKey ruleKey2 =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder).build(parentRule2);

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void subclassWithNoopSetter() {
    class NoopSetterRuleKeyBuilder extends UncachedRuleKeyBuilder {

      public NoopSetterRuleKeyBuilder(
          SourcePathRuleFinder ruleFinder,
          SourcePathResolver pathResolver,
          FileHashCache hashCache,
          RuleKeyFactory<RuleKey> defaultRuleKeyFactory) {
        super(ruleFinder, pathResolver, hashCache, defaultRuleKeyFactory);
      }

      @Override
      protected NoopSetterRuleKeyBuilder setSourcePath(SourcePath sourcePath) {
        return this;
      }
    }

    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache hashCache = new FakeFileHashCache(ImmutableMap.of());
    RuleKeyFactory<RuleKey> ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, pathResolver, ruleFinder);

    RuleKey nullRuleKey =
        new NoopSetterRuleKeyBuilder(ruleFinder, pathResolver, hashCache, ruleKeyFactory)
            .build(RuleKey::new);
    RuleKey noopRuleKey =
        new NoopSetterRuleKeyBuilder(ruleFinder, pathResolver, hashCache, ruleKeyFactory)
            .setReflectively("key", new FakeSourcePath("value"))
            .build(RuleKey::new);

    assertThat(noopRuleKey, is(equalTo(nullRuleKey)));
  }

  @Test
  public void declaredDepsAndExtraDepsGenerateDifferentRuleKeys() {
    SourcePathRuleFinder ruleFinder =
        new SourcePathRuleFinder(
            new BuildRuleResolver(
                TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer()));
    SourcePathResolver sourcePathResolver = new SourcePathResolver(ruleFinder);
    FileHashCache hashCache = new FakeFileHashCache(ImmutableMap.of());
    DefaultRuleKeyFactory ruleKeyFactory =
        new DefaultRuleKeyFactory(0, hashCache, sourcePathResolver, ruleFinder);

    BuildTarget target = BuildTargetFactory.newInstance("//a:target");

    BuildTarget depTarget = BuildTargetFactory.newInstance("//some:dep");
    BuildRuleParams depParams = new FakeBuildRuleParamsBuilder(depTarget).build();
    NoopBuildRule dep = new NoopBuildRule(depParams);

    BuildRuleParams paramsWithDeclaredDep =
        new FakeBuildRuleParamsBuilder(target).setDeclaredDeps(ImmutableSortedSet.of(dep)).build();
    NoopBuildRule ruleWithDeclaredDep = new NoopBuildRule(paramsWithDeclaredDep);

    BuildRuleParams paramsWithExtraDep =
        new FakeBuildRuleParamsBuilder(target).setExtraDeps(ImmutableSortedSet.of(dep)).build();
    NoopBuildRule ruleWithExtraDep = new NoopBuildRule(paramsWithExtraDep);

    BuildRuleParams paramsWithBothDeps =
        new FakeBuildRuleParamsBuilder(target)
            .setDeclaredDeps(ImmutableSortedSet.of(dep))
            .setExtraDeps(ImmutableSortedSet.of(dep))
            .build();
    NoopBuildRule ruleWithBothDeps = new NoopBuildRule(paramsWithBothDeps);

    assertNotEquals(
        ruleKeyFactory.build(ruleWithDeclaredDep), ruleKeyFactory.build(ruleWithExtraDep));
    assertNotEquals(
        ruleKeyFactory.build(ruleWithDeclaredDep), ruleKeyFactory.build(ruleWithBothDeps));
    assertNotEquals(ruleKeyFactory.build(ruleWithExtraDep), ruleKeyFactory.build(ruleWithBothDeps));
  }

  private static class TestRuleKeyAppendable implements RuleKeyAppendable {
    private final String value;

    public TestRuleKeyAppendable(String value) {
      this.value = value;
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("value", value)
          .setReflectively("foo", "foo")
          .setReflectively("bar", "bar");
    }
  }

  private static class TestRuleKeyAppendableBuildRule extends NoopBuildRule {
    private final String foo;

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final String bar;

    public TestRuleKeyAppendableBuildRule(BuildRuleParams buildRuleParams, String foo, String bar) {
      super(buildRuleParams);
      this.foo = foo;
      this.bar = bar;
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("foo", foo);
    }
  }

  private DefaultRuleKeyFactory.Builder<HashCode> createBuilder(
      SourcePathResolver resolver, SourcePathRuleFinder ruleFinder) {
    FileHashCache fileHashCache =
        new FileHashCache() {

          @Override
          public void invalidate(Path path) {}

          @Override
          public void invalidateAll() {}

          @Override
          public HashCode get(Path path) {
            return HashCode.fromString("deadbeef");
          }

          @Override
          public HashCode get(ArchiveMemberPath archiveMemberPath) {
            return HashCode.fromString("deadbeef");
          }

          @Override
          public long getSize(Path path) {
            return 0;
          }

          @Override
          public void set(Path path, HashCode hashCode) {}
        };
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:example");
    BuildRule buildRule = new FakeBuildRule(buildTarget, resolver);
    return new DefaultRuleKeyFactory(0, fileHashCache, resolver, ruleFinder)
        .newBuilderForTesting(buildRule);
  }

  private RuleKeyResult<RuleKey> buildResult(RuleKeyBuilder<HashCode> builder) {
    return ((DefaultRuleKeyFactory.Builder<HashCode>) builder).buildResult(RuleKey::new);
  }
}
