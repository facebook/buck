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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.rules.type.RuleType;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.ForwardingBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.PathSourcePath;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher.Container;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher.Wrapper;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.facebook.buck.util.types.Pair;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameter;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
public final class CommonRuleKeyHasherTest {

  private static final RuleKey RULE_KEY_1 = new RuleKey("a002b39af204cdfaa5fdb67816b13867c32ac52c");
  private static final RuleKey RULE_KEY_2 = new RuleKey("b67816b13867c32ac52ca002b39af204cdfaa5fd");
  private static final BuildTarget TARGET_1 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one");
  private static final BuildTarget TARGET_2 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one#flavor");

  private CommonRuleKeyHasherTest() {}

  @SuppressWarnings("unchecked")
  public static <HASH> List<Object[]> uniquenessTestCases(
      Supplier<RuleKeyHasher<HASH>> newHasher, Pair<String, Number>[] numbers) {
    BiFunction<String, Function<RuleKeyHasher<HASH>, RuleKeyHasher<HASH>>, Pair<String, HASH>>
        pair =
            (description, addToHash) ->
                new Pair<>(description, addToHash.apply(newHasher.get()).hash());

    ImmutableList<Pair<String, HASH>> hashes =
        ImmutableList.<Pair<String, HASH>>builder()
            .add(
                pair.apply("<empty>", h -> h),
                pair.apply("Key<\"\">", h -> h.putKey("")),
                pair.apply("Key<\"42\">", h -> h.putKey("42")),
                pair.apply("Key<\"4\">, Key<\"2\">", h -> h.putKey("4").putKey("2")),
                pair.apply("null", h -> h.putNull()),
                pair.apply("true", h -> h.putBoolean(true)),
                pair.apply("false", h -> h.putBoolean(false)))
            .addAll(
                Stream.of(numbers)
                    .map(num -> pair.apply(num.getFirst(), h -> h.putNumber(num.getSecond())))
                    .iterator())
            .add(
                pair.apply("(char) 0", h -> h.putCharacter((char) 0)),
                pair.apply("(char) 42", h -> h.putCharacter((char) 42)),
                pair.apply("\"\"", h -> h.putString("")),
                pair.apply("\"42\"", h -> h.putString("42")),
                pair.apply("\"4\", \"2\"", h -> h.putString("4").putString("2")),
                pair.apply("new byte[0]", h -> h.putBytes(new byte[0])),
                pair.apply("new byte[]{42}", h -> h.putBytes(new byte[] {42})),
                pair.apply("new byte[]{42, 42}", h -> h.putBytes(new byte[] {42, 42})),
                pair.apply("Pattern<>", h -> h.putPattern(Pattern.compile(""))),
                pair.apply("Pattern<42>", h -> h.putPattern(Pattern.compile("42"))),
                pair.apply(
                    "Pattern<4>",
                    h -> h.putPattern(Pattern.compile("4")).putPattern(Pattern.compile("2"))),
                pair.apply(
                    "Sha1<a002b39af204cdfaa5fdb67816b13867c32ac52c>",
                    h -> h.putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"))),
                pair.apply(
                    "Sha1<b67816b13867c32ac52ca002b39af204cdfaa5fd>",
                    h -> h.putSha1(Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd"))),
                pair.apply("Path<, 0>", h -> h.putPath(Paths.get(""), HashCode.fromInt(0))),
                pair.apply("Path<, 42>", h -> h.putPath(Paths.get(""), HashCode.fromInt(42))),
                pair.apply("Path<42, 0>", h -> h.putPath(Paths.get("42"), HashCode.fromInt(0))),
                pair.apply("Path<42, 42>", h -> h.putPath(Paths.get("42"), HashCode.fromInt(42))),
                pair.apply(
                    "Path<42/42, 42>", h -> h.putPath(Paths.get("42/42"), HashCode.fromInt(42))),
                pair.apply(
                    "ArchiveMember<:, 0>",
                    h -> h.putArchiveMemberPath(newArchiveMember("", ""), HashCode.fromInt(0))),
                pair.apply(
                    "ArchiveMember<:, 0>",
                    h -> h.putArchiveMemberPath(newArchiveMember("", ""), HashCode.fromInt(42))),
                pair.apply(
                    "ArchiveMember<42:42, 0>",
                    h -> h.putArchiveMemberPath(newArchiveMember("42", "42"), HashCode.fromInt(0))),
                pair.apply(
                    "ArchiveMember<42:42, 42>",
                    h ->
                        h.putArchiveMemberPath(newArchiveMember("42", "42"), HashCode.fromInt(42))),
                pair.apply(
                    "ArchiveMember<42/42:42/42, 42>",
                    h ->
                        h.putArchiveMemberPath(
                            newArchiveMember("42/42", "42/42"), HashCode.fromInt(42))),
                pair.apply("NonHashingPath<>", h -> h.putNonHashingPath("")),
                pair.apply("NonHashingPath<42>", h -> h.putNonHashingPath("42")),
                pair.apply(
                    "NonHashingPath<4>, NonHashingPath<2>",
                    h -> h.putNonHashingPath("4").putNonHashingPath("2")),
                pair.apply("SourceRoot<>", h -> h.putSourceRoot(new SourceRoot(""))),
                pair.apply("SourceRoot<42>", h -> h.putSourceRoot(new SourceRoot("42"))),
                pair.apply(
                    "SourceRoot<4>, SourceRoot<2>",
                    h -> h.putSourceRoot(new SourceRoot("4")).putSourceRoot(new SourceRoot("2"))),
                pair.apply(String.format("RuleKey<%s>", RULE_KEY_1), h -> h.putRuleKey(RULE_KEY_1)),
                pair.apply(String.format("RuleKey<%s>", RULE_KEY_2), h -> h.putRuleKey(RULE_KEY_2)),
                pair.apply("RuleType<>", h -> h.putRuleType(RuleType.of(""))),
                pair.apply("RuleType<42>", h -> h.putRuleType(RuleType.of("42"))),
                pair.apply(
                    "RuleType<4>, RuleType<2>",
                    h -> h.putRuleType(RuleType.of("4")).putRuleType(RuleType.of("2"))),
                pair.apply(TARGET_1.toString(), h -> h.putBuildTarget(TARGET_1)),
                pair.apply(TARGET_2.toString(), h -> h.putBuildTarget(TARGET_2)),
                pair.apply(
                    String.format("DefaultBuidTargetSourcePath<%s>", TARGET_1),
                    h -> h.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1))),
                pair.apply(
                    String.format("DefaultBuidTargetSourcePath<%s>", TARGET_2),
                    h -> h.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_2))),
                pair.apply("Container<LIST, 0>", h -> h.putContainer(Container.LIST, 0)),
                pair.apply("Container<LIST, 42>", h -> h.putContainer(Container.LIST, 42)),
                pair.apply("Container<MAP, 0>", h -> h.putContainer(Container.MAP, 0)),
                pair.apply("Container<MAP, 42>", h -> h.putContainer(Container.MAP, 42)),
                pair.apply("Wrapper<SUPPLIER>", h -> h.putWrapper(Wrapper.SUPPLIER)),
                pair.apply("Wrapper<OPTIONAL>", h -> h.putWrapper(Wrapper.OPTIONAL)),
                pair.apply("Wrapper<OPTIONAL_INT>", h -> h.putWrapper(Wrapper.OPTIONAL_INT)),
                pair.apply("Wrapper<EITHER_LEFT>", h -> h.putWrapper(Wrapper.EITHER_LEFT)),
                pair.apply("Wrapper<EITHER_RIGHT>", h -> h.putWrapper(Wrapper.EITHER_RIGHT)),
                pair.apply("Wrapper<BUILD_RULE>", h -> h.putWrapper(Wrapper.BUILD_RULE)),
                pair.apply("Wrapper<APPENDABLE>", h -> h.putWrapper(Wrapper.APPENDABLE)))
            .build();

    List<Object[]> cases = new ArrayList<>();
    for (int i = 0; i < hashes.size(); ++i) {
      Pair<String, HASH> a = hashes.get(i);
      for (int j = 0; j < i; ++j) {
        Pair<String, HASH> b = hashes.get(j);
        cases.add(new Object[] {a.getFirst(), a.getSecond(), b.getFirst(), b.getSecond()});
      }
    }
    return cases;
  }

  @SuppressWarnings("unchecked")
  public static <HASH> List<Object[]> uniquenessTestCases(Supplier<RuleKeyHasher<HASH>> newHasher) {
    return uniquenessTestCases(
        newHasher,
        new Pair[] {
          new Pair<>("(int) 0", 0),
          new Pair<>("(int) 42", 42),
          new Pair<>("(long) 0", (long) 0),
          new Pair<>("(long) 42", (long) 42),
          new Pair<>("(short) 0", (short) 0),
          new Pair<>("(short) 42", (short) 42),
          new Pair<>("(byte) 0", (byte) 0),
          new Pair<>("(byte) 42", (byte) 42),
          new Pair<>("(float) 0", (float) 0),
          new Pair<>("(float) 42", (float) 42),
          new Pair<>("(double) 0", (double) 0),
          new Pair<>("(double) 42", (double) 42),
        });
  }

  public abstract static class UniquenessTest<HASH> {
    @Parameter public String labelA;

    @Parameter(1)
    public HASH hashA;

    @Parameter(2)
    public String labelB;

    @Parameter(3)
    public HASH hashB;

    @Test
    public void testUniqueness() {
      assertThat(String.format("Collison [%s] = [%s]", labelA, labelB), hashA, not(equalTo(hashB)));
    }
  }

  public abstract static class ConsistencyTest<HASH> {
    private static ProjectFilesystem filesystem1;
    private static ProjectFilesystem filesystem2;

    protected abstract RuleKeyHasher<HASH> newHasher();

    @BeforeClass
    public static void setupFileSystems() {
      filesystem1 = new FakeProjectFilesystem(Paths.get("first", "root"));
      filesystem2 = new FakeProjectFilesystem(Paths.get("other", "root"));
    }

    @Test
    public void testEmptyConsistency() {
      assertEquals(newHasher().hash(), newHasher().hash());
    }

    @Test
    public void testConsistencyForKey() {
      assertEquals(newHasher().putKey("abc").hash(), newHasher().putKey("abc").hash());
    }

    @Test
    public void testConsistencyForNull() {
      assertEquals(newHasher().putNull().hash(), newHasher().putNull().hash());
    }

    @Test
    public void testConsistencyForFalse() {
      assertEquals(newHasher().putBoolean(false).hash(), newHasher().putBoolean(false).hash());
    }

    @Test
    public void testConsistencyForTrue() {
      assertEquals(newHasher().putBoolean(true).hash(), newHasher().putBoolean(true).hash());
    }

    @Test
    public void testConsistencyForInt() {
      assertEquals(newHasher().putNumber(4).hash(), newHasher().putNumber(4).hash());
    }

    @Test
    public void testConsistencyForLong() {
      assertEquals(newHasher().putNumber((long) 4).hash(), newHasher().putNumber((long) 4).hash());
    }

    @Test
    public void testConsistencyForShort() {
      assertEquals(
          newHasher().putNumber((short) 4).hash(), newHasher().putNumber((short) 4).hash());
    }

    @Test
    public void testConsistencyForByte() {
      assertEquals(newHasher().putNumber((byte) 4).hash(), newHasher().putNumber((byte) 4).hash());
    }

    @Test
    public void testConsistencyForFloat() {
      assertEquals(
          newHasher().putNumber((float) 4).hash(), newHasher().putNumber((float) 4).hash());
    }

    @Test
    public void testConsistencyForDouble() {
      assertEquals(
          newHasher().putNumber((double) 4).hash(), newHasher().putNumber((double) 4).hash());
    }

    @Test
    public void testConsistencyForBytes() {
      assertEquals(
          newHasher().putBytes(new byte[] {42}).hash(),
          newHasher().putBytes(new byte[] {42}).hash());
    }

    @Test
    public void testConsistencyForPattern() {
      assertEquals(
          newHasher().putPattern(Pattern.compile("42")).hash(),
          newHasher().putPattern(Pattern.compile("42")).hash());
    }

    @Test
    public void testConsistencyForPath() {
      assertEquals(
          newHasher().putPath(Paths.get("42/42"), HashCode.fromInt(42)).hash(),
          newHasher().putPath(Paths.get("42/42"), HashCode.fromInt(42)).hash());
    }

    @Test
    public void testConsistencyForArchiveMemberPath() {
      assertEquals(
          newHasher()
              .putArchiveMemberPath(newArchiveMember("42/42", "42/42"), HashCode.fromInt(42))
              .hash(),
          newHasher()
              .putArchiveMemberPath(newArchiveMember("42/42", "42/42"), HashCode.fromInt(42))
              .hash());
    }

    @Test
    public void testConsistencyForNonHashingPath() {
      assertEquals(
          newHasher().putNonHashingPath("42").hash(), newHasher().putNonHashingPath("42").hash());
    }

    @Test
    public void testConsistencyForSourceRoot() {
      assertEquals(
          newHasher().putSourceRoot(new SourceRoot("42")).hash(),
          newHasher().putSourceRoot(new SourceRoot("42")).hash());
    }

    @Test
    public void testConsistencyForRuleKey() {
      assertEquals(
          newHasher().putRuleKey(RULE_KEY_1).hash(), newHasher().putRuleKey(RULE_KEY_1).hash());
    }

    @Test
    public void testConsistencyForBuildRuleType() {
      assertEquals(
          newHasher().putRuleType(RuleType.of("42")).hash(),
          newHasher().putRuleType(RuleType.of("42")).hash());
    }

    @Test
    public void testConsistencyForBuildTarget() {
      assertEquals(
          newHasher().putBuildTarget(TARGET_1).hash(), newHasher().putBuildTarget(TARGET_1).hash());
    }

    @Test
    public void testConsistencyForBuildTargetSourcePath() {
      assertEquals(
          newHasher().putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)).hash(),
          newHasher().putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)).hash());
    }

    @Test
    public void
        testConsistencyForForwardingBuildTargetSourcePathWithExplicitBuildTargetSourcePath() {
      ForwardingBuildTargetSourcePath forwardingSourcePath1 =
          ForwardingBuildTargetSourcePath.of(
              TARGET_1,
              ExplicitBuildTargetSourcePath.of(
                  TARGET_2, BuildTargetPaths.getGenPath(filesystem1, TARGET_2, "%s.out")));
      ForwardingBuildTargetSourcePath forwardingSourcePath2 =
          ForwardingBuildTargetSourcePath.of(
              TARGET_1,
              ExplicitBuildTargetSourcePath.of(
                  TARGET_2, BuildTargetPaths.getGenPath(filesystem2, TARGET_2, "%s.out")));
      assertEquals(
          newHasher().putBuildTargetSourcePath(forwardingSourcePath1).hash(),
          newHasher().putBuildTargetSourcePath(forwardingSourcePath2).hash());
    }

    @Test
    public void testConsistencyForForwardingBuildTargetSourcePathWithPathSourcePath() {
      Path relativePath = Paths.get("arbitrary", "path");

      ForwardingBuildTargetSourcePath forwardingSourcePath1 =
          ForwardingBuildTargetSourcePath.of(
              TARGET_1, PathSourcePath.of(filesystem1, relativePath));
      ForwardingBuildTargetSourcePath forwardingSourcePath2 =
          ForwardingBuildTargetSourcePath.of(
              TARGET_1, PathSourcePath.of(filesystem2, relativePath));
      assertEquals(
          newHasher().putBuildTargetSourcePath(forwardingSourcePath1).hash(),
          newHasher().putBuildTargetSourcePath(forwardingSourcePath2).hash());
    }
  }

  private static ArchiveMemberPath newArchiveMember(String archivePath, String memberPath) {
    return ArchiveMemberPath.of(Paths.get(archivePath), Paths.get(memberPath));
  }
}
