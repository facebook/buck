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

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.junit.Test;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class GuavaRuleKeyHasherTest {

  private static final RuleKey RULE_KEY_1 = new RuleKey("a002b39af204cdfaa5fdb67816b13867c32ac52c");
  private static final RuleKey RULE_KEY_2 = new RuleKey("b67816b13867c32ac52ca002b39af204cdfaa5fd");
  private static final BuildTarget TARGET_1 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one");
  private static final BuildTarget TARGET_2 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one#flavor");

  @Test
  public void testUniqueness() {
    List<HashCode> hashes = new ArrayList<>();
    hashes.add(newHasher().hash());
    hashes.add(newHasher().putKey("").hash());
    hashes.add(newHasher().putKey("42").hash());
    hashes.add(newHasher().putKey("4").putKey("2").hash());
    hashes.add(newHasher().putNull().hash());
    hashes.add(newHasher().putBoolean(true).hash());
    hashes.add(newHasher().putBoolean(false).hash());
    hashes.add(newHasher().putNumber(0).hash());
    hashes.add(newHasher().putNumber(42).hash());
    hashes.add(newHasher().putNumber((long) 0).hash());
    hashes.add(newHasher().putNumber((long) 42).hash());
    hashes.add(newHasher().putNumber((short) 0).hash());
    hashes.add(newHasher().putNumber((short) 42).hash());
    hashes.add(newHasher().putNumber((byte) 0).hash());
    hashes.add(newHasher().putNumber((byte) 42).hash());
    hashes.add(newHasher().putNumber((float) 0).hash());
    hashes.add(newHasher().putNumber((float) 42).hash());
    hashes.add(newHasher().putNumber((double) 0).hash());
    hashes.add(newHasher().putNumber((double) 42).hash());
    hashes.add(newHasher().putString("").hash());
    hashes.add(newHasher().putString("42").hash());
    hashes.add(newHasher().putString("4").putString("2").hash());
    hashes.add(newHasher().putBytes(new byte[0]).hash());
    hashes.add(newHasher().putBytes(new byte[] {42}).hash());
    hashes.add(newHasher().putBytes(new byte[] {42, 42}).hash());
    hashes.add(newHasher().putPattern(Pattern.compile("")).hash());
    hashes.add(newHasher().putPattern(Pattern.compile("42")).hash());
    hashes.add(
        newHasher().putPattern(Pattern.compile("4")).putPattern(Pattern.compile("2")).hash());
    hashes.add(
        newHasher().putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c")).hash());
    hashes.add(
        newHasher().putSha1(Sha1HashCode.of("b67816b13867c32ac52ca002b39af204cdfaa5fd")).hash());
    hashes.add(newHasher().putPath(Paths.get(""), "").hash());
    hashes.add(newHasher().putPath(Paths.get(""), "42").hash());
    hashes.add(newHasher().putPath(Paths.get("42"), "").hash());
    hashes.add(newHasher().putPath(Paths.get("42"), "42").hash());
    hashes.add(newHasher().putPath(Paths.get("42/42"), "42").hash());
    hashes.add(newHasher().putArchiveMemberPath(newArchiveMember("", ""), "").hash());
    hashes.add(newHasher().putArchiveMemberPath(newArchiveMember("", ""), "42").hash());
    hashes.add(newHasher().putArchiveMemberPath(newArchiveMember("42", "42"), "").hash());
    hashes.add(newHasher().putArchiveMemberPath(newArchiveMember("42", "42"), "42").hash());
    hashes.add(newHasher().putArchiveMemberPath(newArchiveMember("42/42", "42/42"), "42").hash());
    hashes.add(newHasher().putNonHashingPath("").hash());
    hashes.add(newHasher().putNonHashingPath("42").hash());
    hashes.add(newHasher().putNonHashingPath("4").putNonHashingPath("2").hash());
    hashes.add(newHasher().putSourceRoot(new SourceRoot("")).hash());
    hashes.add(newHasher().putSourceRoot(new SourceRoot("42")).hash());
    hashes.add(
        newHasher().putSourceRoot(new SourceRoot("4")).putSourceRoot(new SourceRoot("2")).hash());
    hashes.add(newHasher().putRuleKey(RULE_KEY_1).hash());
    hashes.add(newHasher().putRuleKey(RULE_KEY_2).hash());
    hashes.add(newHasher().putBuildRuleType(BuildRuleType.of("")).hash());
    hashes.add(newHasher().putBuildRuleType(BuildRuleType.of("42")).hash());
    hashes.add(
        newHasher().putBuildRuleType(BuildRuleType.of("4"))
            .putBuildRuleType(BuildRuleType.of("2")).hash());
    hashes.add(newHasher().putBuildTarget(TARGET_1).hash());
    hashes.add(newHasher().putBuildTarget(TARGET_2).hash());
    hashes.add(newHasher().putBuildTargetSourcePath(new BuildTargetSourcePath(TARGET_1)).hash());
    hashes.add(newHasher().putBuildTargetSourcePath(new BuildTargetSourcePath(TARGET_2)).hash());
    hashes.add(newHasher().putContainer(RuleKeyHasher.Container.LIST, 0).hash());
    hashes.add(newHasher().putContainer(RuleKeyHasher.Container.LIST, 42).hash());
    hashes.add(newHasher().putContainer(RuleKeyHasher.Container.MAP, 0).hash());
    hashes.add(newHasher().putContainer(RuleKeyHasher.Container.MAP, 42).hash());
    hashes.add(newHasher().putWrapper(RuleKeyHasher.Wrapper.SUPPLIER).hash());
    hashes.add(newHasher().putWrapper(RuleKeyHasher.Wrapper.OPTIONAL).hash());
    hashes.add(newHasher().putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT).hash());
    hashes.add(newHasher().putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT).hash());
    hashes.add(newHasher().putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE).hash());
    hashes.add(newHasher().putWrapper(RuleKeyHasher.Wrapper.APPENDABLE).hash());
    // all of the hashes should be different
    for (int i = 0; i < hashes.size(); i++) {
      for (int j = 0; j < i; j++) {
        assertNotEquals(String.format("Collision [%d] = [%d]", i, j), hashes.get(i), hashes.get(j));
      }
    }
  }

  @Test
  public void testConsistency() {
    // same sequence of operations should produce the same hash
    assertEquals(newHasher().hash(), newHasher().hash());
    assertEquals(newHasher().putKey("abc").hash(), newHasher().putKey("abc").hash());
    assertEquals(newHasher().putNull().hash(), newHasher().putNull().hash());
    assertEquals(newHasher().putBoolean(false).hash(), newHasher().putBoolean(false).hash());
    assertEquals(newHasher().putBoolean(true).hash(), newHasher().putBoolean(true).hash());
    assertEquals(newHasher().putNumber(4).hash(), newHasher().putNumber(4).hash());
    assertEquals(newHasher().putNumber((long) 4).hash(), newHasher().putNumber((long) 4).hash());
    assertEquals(newHasher().putNumber((short) 4).hash(), newHasher().putNumber((short) 4).hash());
    assertEquals(newHasher().putNumber((byte) 4).hash(), newHasher().putNumber((byte) 4).hash());
    assertEquals(newHasher().putNumber((float) 4).hash(), newHasher().putNumber((float) 4).hash());
    assertEquals(
        newHasher().putNumber((double) 4).hash(),
        newHasher().putNumber((double) 4).hash());
    assertEquals(
        newHasher().putBytes(new byte[] {42}).hash(),
        newHasher().putBytes(new byte[] {42}).hash());
    assertEquals(
        newHasher().putPattern(Pattern.compile("42")).hash(),
        newHasher().putPattern(Pattern.compile("42")).hash());
    assertEquals(
        newHasher().putPath(Paths.get("42/42"), "42").hash(),
        newHasher().putPath(Paths.get("42/42"), "42").hash());
    assertEquals(
        newHasher().putArchiveMemberPath(newArchiveMember("42/42", "42/42"), "42").hash(),
        newHasher().putArchiveMemberPath(newArchiveMember("42/42", "42/42"), "42").hash());
    assertEquals(
        newHasher().putNonHashingPath("42").hash(),
        newHasher().putNonHashingPath("42").hash());
    assertEquals(
        newHasher().putSourceRoot(new SourceRoot("42")).hash(),
        newHasher().putSourceRoot(new SourceRoot("42")).hash());
    assertEquals(
        newHasher().putRuleKey(RULE_KEY_1).hash(),
        newHasher().putRuleKey(RULE_KEY_1).hash());
    assertEquals(
        newHasher().putBuildRuleType(BuildRuleType.of("42")).hash(),
        newHasher().putBuildRuleType(BuildRuleType.of("42")).hash());
    assertEquals(
        newHasher().putBuildTarget(TARGET_1).hash(),
        newHasher().putBuildTarget(TARGET_1).hash());
    assertEquals(
        newHasher().putBuildTargetSourcePath(new BuildTargetSourcePath(TARGET_1)).hash(),
        newHasher().putBuildTargetSourcePath(new BuildTargetSourcePath(TARGET_1)).hash());
  }

  private ArchiveMemberPath newArchiveMember(String archivePath, String memberPath) {
    return ArchiveMemberPath.of(Paths.get(archivePath), Paths.get(memberPath));
  }

  private GuavaRuleKeyHasher newHasher() {
    return new GuavaRuleKeyHasher(Hashing.sha1().newHasher());
  }
}
