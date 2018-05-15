/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.keys;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.cell.TestCellBuilder;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.AddsToRuleKey;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.resolver.impl.SingleThreadedBuildRuleResolver;
import com.facebook.buck.core.rules.transformer.impl.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.core.rules.type.BuildRuleType;
import com.facebook.buck.core.sourcepath.ArchiveMemberSourcePath;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.NonHashableSourcePathContainer;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.junit.Test;

public class RuleKeyBuilderTest {

  private static final BuildTarget TARGET_1 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one");
  private static final BuildTarget TARGET_2 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one#flavor");
  private static final BuildRule IGNORED_RULE = new EmptyFakeBuildRule(TARGET_1);
  private static final BuildRule RULE_1 = new EmptyFakeBuildRule(TARGET_1);
  private static final BuildRule RULE_2 = new EmptyFakeBuildRule(TARGET_2);
  private static final RuleKey RULE_KEY_1 = new RuleKey("a002b39af204cdfaa5fdb67816b13867c32ac52c");
  private static final RuleKey RULE_KEY_2 = new RuleKey("b67816b13867c32ac52ca002b39af204cdfaa5fd");

  private static final AddsToRuleKey IGNORED_APPENDABLE = new FakeRuleKeyAppendable("");
  private static final AddsToRuleKey APPENDABLE_1 = new FakeRuleKeyAppendable("");
  private static final AddsToRuleKey APPENDABLE_2 = new FakeRuleKeyAppendable("42");

  private static final Path PATH_1 = Paths.get("path1");
  private static final Path PATH_2 = Paths.get("path2");
  private static final ProjectFilesystem FILESYSTEM = new FakeProjectFilesystem();
  private static final SourcePath SOURCE_PATH_1 = PathSourcePath.of(FILESYSTEM, PATH_1);
  private static final SourcePath SOURCE_PATH_2 = PathSourcePath.of(FILESYSTEM, PATH_2);
  private static final ArchiveMemberSourcePath ARCHIVE_PATH_1 =
      ArchiveMemberSourcePath.of(SOURCE_PATH_1, Paths.get("member"));
  private static final ArchiveMemberSourcePath ARCHIVE_PATH_2 =
      ArchiveMemberSourcePath.of(SOURCE_PATH_2, Paths.get("member"));
  private static final DefaultBuildTargetSourcePath TARGET_PATH_1 =
      DefaultBuildTargetSourcePath.of(TARGET_1);
  private static final DefaultBuildTargetSourcePath TARGET_PATH_2 =
      DefaultBuildTargetSourcePath.of(TARGET_2);

  @Test
  public void testUniqueness() {
    String[] fieldKeys = new String[] {"key1", "key2"};
    Object[] fieldValues =
        new Object[] {
          // Java types
          null,
          true,
          false,
          0,
          42,
          (long) 0,
          (long) 42,
          (short) 0,
          (short) 42,
          (byte) 0,
          (byte) 42,
          (float) 0,
          (float) 42,
          (double) 0,
          (double) 42,
          (char) 0,
          (char) 42,
          "",
          "42",
          new byte[0],
          new byte[] {42},
          new byte[] {42, 42},
          DummyEnum.BLACK,
          DummyEnum.WHITE,
          Pattern.compile(""),
          Pattern.compile("42"),

          // Buck simple types
          Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"),
          Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd"),
          new SourceRoot(""),
          new SourceRoot("42"),
          RULE_KEY_1,
          RULE_KEY_2,
          BuildRuleType.of(""),
          BuildRuleType.of("42"),
          TARGET_1,
          TARGET_2,

          // Buck paths
          new NonHashableSourcePathContainer(SOURCE_PATH_1),
          new NonHashableSourcePathContainer(SOURCE_PATH_2),
          SOURCE_PATH_1,
          SOURCE_PATH_2,
          ARCHIVE_PATH_1,
          ARCHIVE_PATH_2,
          TARGET_PATH_1,
          TARGET_PATH_2,

          // Buck rules & appendables
          RULE_1,
          RULE_2,
          APPENDABLE_1,
          APPENDABLE_2,

          // Wrappers
          Suppliers.ofInstance(42),
          Optional.of(42),
          Either.ofLeft(42),
          Either.ofRight(42),

          // Containers & nesting
          ImmutableList.of(42),
          ImmutableList.of(42, 42),
          ImmutableMap.of(42, 42),
          ImmutableList.of(ImmutableList.of(1, 2, 3, 4)),
          ImmutableList.of(ImmutableList.of(1, 2), ImmutableList.of(3, 4)),
        };

    List<RuleKey> ruleKeys = new ArrayList<>();
    List<String> desc = new ArrayList<>();
    ruleKeys.add(newBuilder().build(RuleKey::new));
    desc.add("<empty>");
    for (String key : fieldKeys) {
      for (Object val : fieldValues) {
        ruleKeys.add(calcRuleKey(key, val));
        String clazz = (val != null) ? val.getClass().getSimpleName() : "";
        desc.add(String.format("{key=%s, val=%s{%s}}", key, clazz, val));
      }
    }
    // all of the rule keys should be different
    for (int i = 0; i < ruleKeys.size(); i++) {
      for (int j = 0; j < i; j++) {
        assertNotEquals(
            String.format("Collision: %s == %s", desc.get(i), desc.get(j)),
            ruleKeys.get(i),
            ruleKeys.get(j));
      }
    }
  }

  @Test
  public void testNoOp() {
    RuleKey noop = newBuilder().build(RuleKey::new);
    assertEquals(noop, calcRuleKey("key", ImmutableList.of()));
    assertEquals(noop, calcRuleKey("key", ImmutableList.of().iterator()));
    assertEquals(noop, calcRuleKey("key", ImmutableMap.of()));
    assertEquals(noop, calcRuleKey("key", ImmutableList.of(IGNORED_RULE)));
    assertEquals(noop, calcRuleKey("key", Suppliers.ofInstance(IGNORED_RULE)));
    assertEquals(noop, calcRuleKey("key", Optional.of(IGNORED_RULE)));
    assertEquals(noop, calcRuleKey("key", Either.ofLeft(IGNORED_RULE)));
    assertEquals(noop, calcRuleKey("key", Either.ofRight(IGNORED_RULE)));
    assertEquals(noop, calcRuleKey("key", IGNORED_RULE));
    assertEquals(noop, calcRuleKey("key", IGNORED_APPENDABLE));
  }

  private RuleKey calcRuleKey(String key, @Nullable Object val) {
    return newBuilder().setReflectively(key, val).build(RuleKey::new);
  }

  private RuleKeyBuilder<HashCode> newBuilder() {
    Map<BuildTarget, BuildRule> ruleMap = ImmutableMap.of(TARGET_1, RULE_1, TARGET_2, RULE_2);
    Map<BuildRule, RuleKey> ruleKeyMap = ImmutableMap.of(RULE_1, RULE_KEY_1, RULE_2, RULE_KEY_2);
    Map<AddsToRuleKey, RuleKey> appendableKeys =
        ImmutableMap.of(APPENDABLE_1, RULE_KEY_1, APPENDABLE_2, RULE_KEY_2);
    BuildRuleResolver ruleResolver = new FakeBuildRuleResolver(ruleMap);
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    FakeFileHashCache hashCache =
        new FakeFileHashCache(
            ImmutableMap.of(
                FILESYSTEM.resolve(PATH_1), HashCode.fromInt(0),
                FILESYSTEM.resolve(PATH_2), HashCode.fromInt(42)),
            ImmutableMap.of(
                pathResolver.getAbsoluteArchiveMemberPath(ARCHIVE_PATH_1), HashCode.fromInt(0),
                pathResolver.getAbsoluteArchiveMemberPath(ARCHIVE_PATH_2), HashCode.fromInt(42)),
            ImmutableMap.of());

    return new RuleKeyBuilder<HashCode>(
        ruleFinder, pathResolver, hashCache, RuleKeyBuilder.createDefaultHasher(Optional.empty())) {

      @Override
      protected RuleKeyBuilder<HashCode> setBuildRule(BuildRule rule) {
        if (rule == IGNORED_RULE) {
          return this;
        }
        return setBuildRuleKey(ruleKeyMap.get(rule));
      }

      @Override
      public RuleKeyBuilder<HashCode> setAddsToRuleKey(AddsToRuleKey appendable) {
        if (appendable == IGNORED_APPENDABLE) {
          return this;
        }
        return setAddsToRuleKey(appendableKeys.get(appendable));
      }

      @Override
      protected RuleKeyBuilder<HashCode> setSourcePath(SourcePath sourcePath) throws IOException {
        if (sourcePath instanceof BuildTargetSourcePath) {
          return setSourcePathAsRule((BuildTargetSourcePath) sourcePath);
        } else {
          return setSourcePathDirectly(sourcePath);
        }
      }

      @Override
      protected RuleKeyBuilder<HashCode> setNonHashingSourcePath(SourcePath sourcePath) {
        return setNonHashingSourcePathDirectly(sourcePath);
      }
    };
  }

  // This ugliness is necessary as we don't have mocks in Buck unit tests.
  private static class FakeBuildRuleResolver extends SingleThreadedBuildRuleResolver {
    private final Map<BuildTarget, BuildRule> ruleMap;

    public FakeBuildRuleResolver(Map<BuildTarget, BuildRule> ruleMap) {
      super(
          TargetGraph.EMPTY,
          new DefaultTargetNodeToBuildRuleTransformer(),
          new TestCellBuilder().build().getCellProvider());
      this.ruleMap = ruleMap;
    }

    @Override
    public BuildRule getRule(BuildTarget target) {
      return Preconditions.checkNotNull(ruleMap.get(target), "No rule for target: " + target);
    }
  }

  private static class FakeRuleKeyAppendable implements AddsToRuleKey {

    @AddToRuleKey private final String field;

    public FakeRuleKeyAppendable(String field) {
      this.field = field;
    }
  }

  private enum DummyEnum {
    BLACK,
    WHITE,
  }
}
