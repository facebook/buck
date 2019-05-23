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

package com.facebook.buck.core.rulekey;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.targetgraph.FakeTargetNodeBuilder;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.TestBuildRuleParams;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.rules.impl.FakeBuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.rules.resolver.impl.TestActionGraphBuilder;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.FakeSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleImmutable;
import com.facebook.buck.core.util.immutables.BuckStylePackageVisibleTuple;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.io.filesystem.impl.FakeProjectFilesystem;
import com.facebook.buck.log.ConsoleHandler;
import com.facebook.buck.rules.keys.AbstractRuleKeyBuilder;
import com.facebook.buck.rules.keys.DefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyBuilder;
import com.facebook.buck.rules.keys.RuleKeyDiagnostics.Result;
import com.facebook.buck.rules.keys.RuleKeyFactory;
import com.facebook.buck.rules.keys.RuleKeyResult;
import com.facebook.buck.rules.keys.TestDefaultRuleKeyFactory;
import com.facebook.buck.rules.keys.UncachedRuleKeyBuilder;
import com.facebook.buck.rules.keys.hasher.StringRuleKeyHasher;
import com.facebook.buck.step.Step;
import com.facebook.buck.testutil.DummyFileHashCache;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.util.cache.FileHashCache;
import com.facebook.buck.util.cache.FileHashCacheMode;
import com.facebook.buck.util.cache.impl.DefaultFileHashCache;
import com.facebook.buck.util.cache.impl.StackedFileHashCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.hamcrest.Matchers;
import org.immutables.value.Value;
import org.junit.Test;

public class RuleKeyTest {

  @Test
  public void testRuleKeyFromHashString() {
    RuleKey ruleKey = new RuleKey("19d2558a6bd3a34fb3f95412de9da27ed32fe208");
    assertEquals("19d2558a6bd3a34fb3f95412de9da27ed32fe208", ruleKey.toString());
  }

  @Test(expected = HumanReadableException.class)
  public void shouldNotAllowPathsInRuleKeysWhenSetReflectively() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKeyBuilder<HashCode> builder = createBuilder(ruleFinder);

    builder.setReflectively("path", Paths.get("some/path"));
  }

  /** Ensure that build rules with the same inputs but different deps have unique RuleKeys. */
  @Test
  public void testRuleKeyDependsOnDeps() {
    FakeProjectFilesystem filesystem = new FakeProjectFilesystem();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    filesystem, FileHashCacheMode.DEFAULT)));
    ActionGraphBuilder graphBuilder1 = new TestActionGraphBuilder();
    ActionGraphBuilder graphBuilder2 = new TestActionGraphBuilder();
    DefaultRuleKeyFactory ruleKeyFactory = new TestDefaultRuleKeyFactory(hashCache, graphBuilder1);
    DefaultRuleKeyFactory ruleKeyFactory2 = new TestDefaultRuleKeyFactory(hashCache, graphBuilder2);

    // Create a dependent build rule, //src/com/facebook/buck/cli:common.
    FakeTargetNodeBuilder builder =
        FakeTargetNodeBuilder.newBuilder(
            BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:common"));
    BuildRule commonJavaLibrary = builder.build(graphBuilder1);
    builder.build(graphBuilder2);

    // Create a java_library() rule with no deps.
    FakeTargetNodeBuilder dependentBuilder =
        FakeTargetNodeBuilder.newBuilder(
            BuildTargetFactory.newInstance("//src/com/facebook/buck/cli:cli"));
    BuildRule libraryNoCommon = dependentBuilder.build(graphBuilder1, filesystem);

    // Create the same java_library() rule, but with a dep on //src/com/facebook/buck/cli:common.
    dependentBuilder.setDeps(commonJavaLibrary.getBuildTarget());
    BuildRule libraryWithCommon = dependentBuilder.build(graphBuilder2, filesystem);

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
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey reflective =
        createBuilder(ruleFinder)
            .setReflectively("long", 42L)
            .setReflectively("boolean", true)
            .setReflectively("path", FakeSourcePath.of("location/of/the/rebel/plans"))
            .build(RuleKey::new);

    RuleKey manual =
        createBuilder(ruleFinder)
            .setReflectively("long", 42L)
            .setReflectively("boolean", true)
            .setReflectively("path", FakeSourcePath.of("location/of/the/rebel/plans"))
            .build(RuleKey::new);

    assertEquals(manual, reflective);
  }

  @Test
  public void ensureTwoListsOfSameRuleKeyAppendablesHaveSameRuleKey() {
    ImmutableList<TestRuleKeyAppendable> ruleKeyAppendableList =
        ImmutableList.of(new TestRuleKeyAppendable("foo"), new TestRuleKeyAppendable("bar"));

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey ruleKeyPairA =
        createBuilder(ruleFinder)
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableList)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(ruleFinder)
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

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey ruleKeyPairA =
        createBuilder(ruleFinder)
            .setReflectively("ruleKeyAppendableList", ruleKeyAppendableListA)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(ruleFinder)
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

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey ruleKeyPairA =
        createBuilder(ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMap)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(ruleFinder)
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

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey ruleKeyPairA =
        createBuilder(ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMapA)
            .build(RuleKey::new);

    RuleKey ruleKeyPairB =
        createBuilder(ruleFinder)
            .setReflectively("ruleKeyAppendableMap", ruleKeyAppendableMapB)
            .build(RuleKey::new);

    assertNotEquals(ruleKeyPairA, ruleKeyPairB);
  }

  @Test
  public void ensureListsAreHandledProperly() {
    ImmutableList<String> strings = ImmutableList.of("one", "two");

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey reflective =
        createBuilder(ruleFinder).setReflectively("strings", strings).build(RuleKey::new);

    RuleKey manual =
        createBuilder(ruleFinder).setReflectively("strings", strings).build(RuleKey::new);

    assertEquals(manual, reflective);
  }

  @Test
  public void differentSeedsMakeDifferentKeys() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();

    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:example");
    BuildRule buildRule = new FakeBuildRule(buildTarget);

    RuleKey empty1 =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), ruleFinder).build(buildRule);
    RuleKey empty2 =
        new TestDefaultRuleKeyFactory(new DummyFileHashCache(), ruleFinder).build(buildRule);
    RuleKey empty3 =
        new TestDefaultRuleKeyFactory(1, new DummyFileHashCache(), ruleFinder).build(buildRule);

    assertThat(empty1, is(equalTo(empty2)));
    assertThat(empty1, is(not(equalTo(empty3))));
  }

  @Test
  public void testRuleKeyEqualsAndHashCodeMethods() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey keyPair1 =
        createBuilder(ruleFinder).setReflectively("something", "foo").build(RuleKey::new);
    RuleKey keyPair2 =
        createBuilder(ruleFinder).setReflectively("something", "foo").build(RuleKey::new);
    RuleKey keyPair3 =
        createBuilder(ruleFinder).setReflectively("something", "bar").build(RuleKey::new);
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

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    // Changing the name of a named source path should change the hash...
    assertNotEquals(
        buildResult(
            createBuilder(ruleFinder)
                .setReflectively(
                    "key", PathSourcePath.of(projectFilesystem, Paths.get("something")))),
        buildResult(
            createBuilder(ruleFinder)
                .setReflectively(
                    "key", PathSourcePath.of(projectFilesystem, Paths.get("something", "else")))));

    // ... as should changing the key
    assertNotEquals(
        buildResult(
            createBuilder(ruleFinder)
                .setReflectively(
                    "key", PathSourcePath.of(projectFilesystem, Paths.get("something")))),
        buildResult(
            createBuilder(ruleFinder)
                .setReflectively(
                    "different-key",
                    PathSourcePath.of(projectFilesystem, Paths.get("something")))));
  }

  @Test
  public void setNonHashingSourcePathsWithDifferentRelativePaths() {
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    PathSourcePath sourcePathOne = FakeSourcePath.of(projectFilesystem, "something");
    PathSourcePath sourcePathTwo = FakeSourcePath.of(projectFilesystem, "something2");

    // Changing the relative path should change the rule key
    SourcePathRuleFinder ruleFinder1 = new TestActionGraphBuilder();
    SourcePathRuleFinder ruleFinder2 = new TestActionGraphBuilder();
    assertNotEquals(
        buildResult(
            createBuilder(ruleFinder1)
                .setReflectively("key", new NonHashableSourcePathContainer(sourcePathOne))),
        buildResult(
            createBuilder(ruleFinder2)
                .setReflectively("key", new NonHashableSourcePathContainer(sourcePathTwo))));
  }

  @Test
  public void setInputBuildTargetSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();
    FakeBuildRule fake1 = new FakeBuildRule("//:fake1");
    FakeBuildRule fake2 = new FakeBuildRule("//:fake2");
    graphBuilder.addToIndex(fake1);
    graphBuilder.addToIndex(fake2);

    // Verify that two BuildTargetSourcePaths with the same rule and path are equal.
    assertEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("location")))));

    // Verify that just changing the path of the build rule changes the rule key.
    assertNotEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("different")))));

    // Verify that just changing the build rule rule key changes the calculated rule key.
    assertNotEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake2.getBuildTarget(), Paths.get("location")))));

    // Verify that just changing the key changes the calculated rule key.
    assertNotEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "different-key",
                    ExplicitBuildTargetSourcePath.of(
                        fake1.getBuildTarget(), Paths.get("location")))));
  }

  @Test
  public void setInputArchiveMemberSourcePath() {
    ActionGraphBuilder graphBuilder = new TestActionGraphBuilder();

    FakeBuildRule fakeBuildRule = new FakeBuildRule("//:fake");
    graphBuilder.addToIndex(fakeBuildRule);

    ExplicitBuildTargetSourcePath archive1 =
        ExplicitBuildTargetSourcePath.of(fakeBuildRule.getBuildTarget(), Paths.get("location"));
    PathSourcePath archive2 = FakeSourcePath.of("otherLocation");

    // Verify that two ArchiveMemberSourcePaths with the same archive and path
    assertEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))));

    // Verify that just changing the archive changes the rule key
    assertNotEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive2, Paths.get("location")))));

    // Verify that just changing the member path changes the rule key
    assertNotEquals(
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("location")))),
        buildResult(
            createBuilder(graphBuilder)
                .setReflectively(
                    "key", ArchiveMemberSourcePath.of(archive1, Paths.get("different")))));
  }

  @Test
  public void canAddMapsToRuleKeys() {
    ImmutableMap<String, ?> map =
        ImmutableMap.of("path", FakeSourcePath.of("some/path"), "boolean", true);

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey key = createBuilder(ruleFinder).setReflectively("map", map).build(RuleKey::new);

    assertNotNull(key);
  }

  @Test
  public void keysOfMapsAddedToRuleKeysDoNotNeedToBeStrings() {
    ImmutableMap<?, ?> map =
        ImmutableMap.of(
            FakeSourcePath.of("some/path"), "woohoo!", 42L, "life, the universe and everything");

    SourcePathRuleFinder pathRuleFinder = new TestActionGraphBuilder();
    RuleKey key = createBuilder(pathRuleFinder).setReflectively("map", map).build(RuleKey::new);

    assertNotNull(key);
  }

  @Test
  public void canAddRuleKeyAppendable() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey key =
        createBuilder(ruleFinder)
            .setReflectively("rule_key_appendable", new TestRuleKeyAppendable("foo"))
            .build(RuleKey::new);
    assertNotNull(key);
  }

  @Test
  public void canAddListOfRuleKeyAppendable() {
    ImmutableList<TestRuleKeyAppendable> list =
        ImmutableList.of(new TestRuleKeyAppendable("foo"), new TestRuleKeyAppendable("bar"));
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey key = createBuilder(ruleFinder).setReflectively("list", list).build(RuleKey::new);
    assertNotNull(key);
  }

  @Test
  public void canAddMapOfRuleKeyAppendable() {
    ImmutableMap<String, TestRuleKeyAppendable> map =
        ImmutableMap.of(
            "foo", new TestRuleKeyAppendable("foo"),
            "bar", new TestRuleKeyAppendable("bar"));
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey key = createBuilder(ruleFinder).setReflectively("map", map).build(RuleKey::new);
    assertNotNull(key);
  }

  @Test
  public void changingRuleKeyFieldChangesKeyWhenClassImplementsAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    new FakeProjectFilesystem(), FileHashCacheMode.DEFAULT)));

    BuildRule buildRule1 =
        new TestRuleKeyAppendableBuildRule(target, projectFilesystem, params, "foo", "bar");
    BuildRule buildRule2 =
        new TestRuleKeyAppendableBuildRule(target, projectFilesystem, params, "foo", "xyzzy");

    RuleKey ruleKey1 = new TestDefaultRuleKeyFactory(hashCache, ruleFinder).build(buildRule1);
    RuleKey ruleKey2 = new TestDefaultRuleKeyFactory(hashCache, ruleFinder).build(buildRule2);

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void ruleKeyIncludesClass() {
    class AddsToRuleKey1 implements AddsToRuleKey {}
    class AddsToRuleKey2 implements AddsToRuleKey {}
    class SimpleBuildRule extends AbstractBuildRule {
      @AddToRuleKey final AddsToRuleKey value;

      protected SimpleBuildRule(
          BuildTarget buildTarget, ProjectFilesystem projectFilesystem, AddsToRuleKey value) {
        super(buildTarget, projectFilesystem);
        this.value = value;
      }

      @Override
      public SortedSet<BuildRule> getBuildDeps() {
        return ImmutableSortedSet.of();
      }

      @Override
      public ImmutableList<? extends Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return ImmutableList.of();
      }

      @Nullable
      @Override
      public SourcePath getSourcePathToOutput() {
        return null;
      }
    }
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    new FakeProjectFilesystem(), FileHashCacheMode.DEFAULT)));

    RuleKey ruleKey1 =
        new TestDefaultRuleKeyFactory(0, hashCache, ruleFinder)
            .build(new SimpleBuildRule(target, projectFilesystem, new AddsToRuleKey1()));
    RuleKey ruleKey2 =
        new TestDefaultRuleKeyFactory(0, hashCache, ruleFinder)
            .build(new SimpleBuildRule(target, projectFilesystem, new AddsToRuleKey2()));

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void changingRuleKeyFieldOfDepChangesKeyWhenClassImplementsAppendToRuleKey() {
    BuildTarget target = BuildTargetFactory.newInstance("//cheese:peas");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams params = TestBuildRuleParams.create();
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    FileHashCache hashCache =
        new StackedFileHashCache(
            ImmutableList.of(
                DefaultFileHashCache.createDefaultFileHashCache(
                    new FakeProjectFilesystem(), FileHashCacheMode.DEFAULT)));

    BuildRule buildRule1 =
        new TestRuleKeyAppendableBuildRule(target, projectFilesystem, params, "foo", "bar");
    BuildRule buildRule2 =
        new TestRuleKeyAppendableBuildRule(target, projectFilesystem, params, "foo", "xyzzy");

    BuildTarget parentTarget = BuildTargetFactory.newInstance("//cheese:milk");

    BuildRuleParams parentParams1 =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(buildRule1));
    BuildRule parentRule1 =
        new NoopBuildRuleWithDeclaredAndExtraDeps(parentTarget, projectFilesystem, parentParams1);
    BuildRuleParams parentParams2 =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(buildRule2));
    BuildRule parentRule2 =
        new NoopBuildRuleWithDeclaredAndExtraDeps(parentTarget, projectFilesystem, parentParams2);

    RuleKey ruleKey1 = new TestDefaultRuleKeyFactory(hashCache, ruleFinder).build(parentRule1);
    RuleKey ruleKey2 = new TestDefaultRuleKeyFactory(hashCache, ruleFinder).build(parentRule2);

    assertNotEquals(ruleKey1, ruleKey2);
  }

  @Test
  public void subclassWithNoopSetter() {
    class NoopSetterRuleKeyBuilder extends UncachedRuleKeyBuilder {

      public NoopSetterRuleKeyBuilder(
          SourcePathRuleFinder ruleFinder,
          FileHashCache hashCache,
          RuleKeyFactory<RuleKey> defaultRuleKeyFactory) {
        super(ruleFinder, hashCache, defaultRuleKeyFactory);
      }

      @Override
      protected NoopSetterRuleKeyBuilder setSourcePath(SourcePath sourcePath) {
        return this;
      }
    }

    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    FileHashCache hashCache = new FakeFileHashCache(ImmutableMap.of());
    RuleKeyFactory<RuleKey> ruleKeyFactory = new TestDefaultRuleKeyFactory(hashCache, ruleFinder);

    RuleKey nullRuleKey =
        new NoopSetterRuleKeyBuilder(ruleFinder, hashCache, ruleKeyFactory).build(RuleKey::new);
    RuleKey noopRuleKey =
        new NoopSetterRuleKeyBuilder(ruleFinder, hashCache, ruleKeyFactory)
            .setReflectively("key", FakeSourcePath.of("value"))
            .build(RuleKey::new);

    assertThat(noopRuleKey, is(equalTo(nullRuleKey)));
  }

  @Test
  public void declaredDepsAndExtraDepsGenerateDifferentRuleKeys() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    FileHashCache hashCache = new FakeFileHashCache(ImmutableMap.of());
    DefaultRuleKeyFactory ruleKeyFactory = new TestDefaultRuleKeyFactory(hashCache, ruleFinder);

    BuildTarget target = BuildTargetFactory.newInstance("//a:target");

    BuildTarget depTarget = BuildTargetFactory.newInstance("//some:dep");
    ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
    BuildRuleParams depParams = TestBuildRuleParams.create();
    NoopBuildRuleWithDeclaredAndExtraDeps dep =
        new NoopBuildRuleWithDeclaredAndExtraDeps(depTarget, projectFilesystem, depParams);

    BuildRuleParams paramsWithDeclaredDep =
        TestBuildRuleParams.create().withDeclaredDeps(ImmutableSortedSet.of(dep));
    NoopBuildRuleWithDeclaredAndExtraDeps ruleWithDeclaredDep =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, projectFilesystem, paramsWithDeclaredDep);

    BuildRuleParams paramsWithExtraDep =
        TestBuildRuleParams.create().withExtraDeps(ImmutableSortedSet.of(dep));
    NoopBuildRuleWithDeclaredAndExtraDeps ruleWithExtraDep =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, projectFilesystem, paramsWithExtraDep);

    BuildRuleParams paramsWithBothDeps =
        TestBuildRuleParams.create()
            .withDeclaredDeps(ImmutableSortedSet.of(dep))
            .withExtraDeps(ImmutableSortedSet.of(dep));
    NoopBuildRuleWithDeclaredAndExtraDeps ruleWithBothDeps =
        new NoopBuildRuleWithDeclaredAndExtraDeps(target, projectFilesystem, paramsWithBothDeps);

    assertNotEquals(
        ruleKeyFactory.build(ruleWithDeclaredDep), ruleKeyFactory.build(ruleWithExtraDep));
    assertNotEquals(
        ruleKeyFactory.build(ruleWithDeclaredDep), ruleKeyFactory.build(ruleWithBothDeps));
    assertNotEquals(ruleKeyFactory.build(ruleWithExtraDep), ruleKeyFactory.build(ruleWithBothDeps));
  }

  @Test
  public void immutablesCanAddValueMethodsFromInterfaceImmutablesToRuleKeys() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey first =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyInterfaceImmutable.of("added-1", "ignored-1"))
            .build(RuleKey::new);

    RuleKey second =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyInterfaceImmutable.of("added-1", "ignored-2"))
            .build(RuleKey::new);

    RuleKey third =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyInterfaceImmutable.of("added-2", "ignored-2"))
            .build(RuleKey::new);

    assertEquals(first, second);
    assertNotEquals(first, third);
  }

  @Test
  public void lambdaAddsPseudoClassName() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    Result<RuleKey, String> result =
        createFactory(ruleFinder)
            .buildForDiagnostics((RuleKeyAppendable) (sink) -> {}, new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            "string(\"com.facebook.buck.core.rulekey.RuleKeyTest$?????\"):key(.class)"));
  }

  @Test
  public void anonymousClassAddsPseudoClassName() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    Result<RuleKey, String> result =
        createFactory(ruleFinder)
            .buildForDiagnostics(new AddsToRuleKey() {}, new StringRuleKeyHasher());
    assertThat(
        result.diagKey,
        Matchers.containsString(
            "string(\"com.facebook.buck.core.rulekey.RuleKeyTest$?????\"):key(.class)"));
  }

  @Value.Immutable
  @BuckStyleTuple
  interface AbstractTestRuleKeyInterfaceImmutable extends AddsToRuleKey {
    @AddToRuleKey
    String getRuleKeyValue();

    String getNonRuleKeyValue();
  }

  @Test
  public void immutablesCanAddNonDefaultImmutableValues() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey first =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyImmutableWithDefaults.builder().build())
            .build(RuleKey::new);

    RuleKey second =
        createBuilder(ruleFinder)
            .setReflectively(
                "value",
                TestRuleKeyImmutableWithDefaults.builder().setRuleKeyValue("other").build())
            .build(RuleKey::new);

    assertNotEquals(first, second);
  }

  @Value.Immutable
  @BuckStyleImmutable
  abstract static class AbstractTestRuleKeyImmutableWithDefaults implements AddsToRuleKey {
    @AddToRuleKey
    @Value.Default
    String getRuleKeyValue() {
      return "default";
    }
  }

  @Test
  public void immutablesCanAddValueMethodsFromExtendedInterfaceImmutablesToRuleKeys() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    RuleKey first =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyAbstractImmutable.of("added-1", "ignored-1"))
            .build(RuleKey::new);

    RuleKey second =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyAbstractImmutable.of("added-1", "ignored-2"))
            .build(RuleKey::new);

    RuleKey third =
        createBuilder(ruleFinder)
            .setReflectively("value", TestRuleKeyAbstractImmutable.of("added-2", "ignored-2"))
            .build(RuleKey::new);

    assertEquals(first, second);
    assertNotEquals(first, third);
  }

  @Value.Immutable
  @BuckStylePackageVisibleTuple
  abstract static class AbstractTestPackageVisibleTuple implements AddsToRuleKey {
    @AddToRuleKey
    abstract int getValue();
  }

  @Value.Immutable
  @BuckStylePackageVisibleImmutable
  abstract static class AbstractTestPackageVisibleImmutable implements AddsToRuleKey {
    @AddToRuleKey
    abstract int getValue();
  }

  @Test
  public void packageVisibleImmutablesCanUseAddToRuleKey() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    createBuilder(ruleFinder)
        .setReflectively("value", TestPackageVisibleTuple.of(0))
        .build(RuleKey::new);

    createBuilder(ruleFinder)
        .setReflectively("value", TestPackageVisibleImmutable.builder().setValue(0).build())
        .build(RuleKey::new);
  }

  @Value.Immutable
  @BuckStyleTuple
  abstract static class AbstractTestRuleKeyAbstractImmutable implements AddsToRuleKey {
    @AddToRuleKey
    abstract String getRuleKeyValue();

    abstract String getNonRuleKeyValue();
  }

  @Test(expected = UncheckedExecutionException.class)
  public void badUseOfAddValueMethodsToRuleKey() {
    java.util.logging.Logger.getGlobal().addHandler(new ConsoleHandler());
    SourcePathRuleFinder pathRuleFinder = new TestActionGraphBuilder();
    createBuilder(pathRuleFinder)
        .setReflectively("value", (BadUseOfAddValueMethodsToRuleKey) () -> "")
        .build(RuleKey::new);
  }

  interface BadUseOfAddValueMethodsToRuleKey extends AddsToRuleKey {
    @AddToRuleKey
    String whatever();
  }

  interface EmptyInterface {}

  interface ExtendsBadUseAndOther extends EmptyInterface, BadUseOfAddValueMethodsToRuleKey {}

  abstract class EmptyClass {}

  abstract class ExtendsFurtherBadUseAndOther extends EmptyClass
      implements EmptyInterface, ExtendsBadUseAndOther {}

  @Test(expected = UncheckedExecutionException.class)
  public void badUseOfAddValueMethodsToRuleKeyInHierarchy() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    createBuilder(ruleFinder)
        .setReflectively("value", new ClassWithBadThingInHierarchy())
        .build(RuleKey::new);
  }

  class ClassWithBadThingInHierarchy extends ExtendsFurtherBadUseAndOther {
    @Override
    public String whatever() {
      return null;
    }
  }

  @Test(expected = UncheckedExecutionException.class)
  public void badUseOfAddValueMethodsToRuleKeyInSomeSuperInterface() {
    SourcePathRuleFinder ruleFinder = new TestActionGraphBuilder();
    createBuilder(ruleFinder)
        .setReflectively(
            "value",
            new DerivedFromImplementsBadUseOfAddValueMethodsToRuleKey() {
              @Override
              public String whatever() {
                return null;
              }
            })
        .build(RuleKey::new);
  }

  abstract class ImplementsBadUseOfAddValueMethodsToRuleKey
      implements BadUseOfAddValueMethodsToRuleKey {}

  abstract class DerivedFromImplementsBadUseOfAddValueMethodsToRuleKey
      extends ImplementsBadUseOfAddValueMethodsToRuleKey {}

  private static class TestRuleKeyAppendable implements AddsToRuleKey {
    @AddToRuleKey private final String value;
    @AddToRuleKey private final String foo = "foo";
    @AddToRuleKey private final String bar = "bar";

    public TestRuleKeyAppendable(String value) {
      this.value = value;
    }
  }

  private static class TestRuleKeyAppendableBuildRule
      extends NoopBuildRuleWithDeclaredAndExtraDeps {
    private final String foo;

    @SuppressWarnings("PMD.UnusedPrivateField")
    @AddToRuleKey
    private final String bar;

    public TestRuleKeyAppendableBuildRule(
        BuildTarget buildTarget,
        ProjectFilesystem projectFilesystem,
        BuildRuleParams buildRuleParams,
        String foo,
        String bar) {
      super(buildTarget, projectFilesystem, buildRuleParams);
      this.foo = foo;
      this.bar = bar;
    }

    @Override
    public void appendToRuleKey(RuleKeyObjectSink sink) {
      sink.setReflectively("foo", foo);
    }
  }

  private DefaultRuleKeyFactory.Builder<HashCode> createBuilder(SourcePathRuleFinder ruleFinder) {
    TestDefaultRuleKeyFactory factory = createFactory(ruleFinder);
    BuildTarget buildTarget = BuildTargetFactory.newInstance("//some:example");
    BuildRule buildRule = new FakeBuildRule(buildTarget);
    return factory.newBuilderForTesting(buildRule);
  }

  private TestDefaultRuleKeyFactory createFactory(SourcePathRuleFinder ruleFinder) {
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
          public HashCode getForArchiveMember(Path relativeArchivePath, Path memberPath) {
            return HashCode.fromString("deadbeef");
          }

          @Override
          public long getSize(Path path) {
            return 0;
          }

          @Override
          public void set(Path path, HashCode hashCode) {}
        };
    return new TestDefaultRuleKeyFactory(fileHashCache, ruleFinder);
  }

  private RuleKeyResult<RuleKey> buildResult(AbstractRuleKeyBuilder<HashCode> builder) {
    return ((DefaultRuleKeyFactory.Builder<HashCode>) builder).buildResult(RuleKey::new);
  }
}
