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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertSame;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.BuildTargetFactory;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.DefaultBuildTargetSourcePath;
import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.rules.keys.hasher.ForwardingRuleKeyHasher;
import com.facebook.buck.rules.keys.hasher.RuleKeyHasher;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import java.nio.file.Paths;
import java.util.regex.Pattern;
import org.junit.Test;

public class ForwardingRuleKeyHasherTest {

  private static final RuleKey RULE_KEY_1 = new RuleKey("a002b39af204cdfaa5fdb67816b13867c32ac52c");
  private static final BuildTarget TARGET_1 =
      BuildTargetFactory.newInstance(Paths.get("/root"), "//example/base:one");
  private static final byte[] BYTE_ARRAY = new byte[] {42, 42};
  private static final Pattern PATTERN = Pattern.compile("42");

  @Test
  public void testForwarding() {
    String string = "hash";
    HashCode hash = createMock(HashCode.class);

    @SuppressWarnings("unchecked")
    RuleKeyHasher<String> stringHasher = createStrictMock(RuleKeyHasher.class);
    @SuppressWarnings("unchecked")
    RuleKeyHasher<HashCode> guavaHasher = createStrictMock(RuleKeyHasher.class);

    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putKey("42")).andReturn(guavaHasher);
    expect(stringHasher.putKey("42")).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNull()).andReturn(guavaHasher);
    expect(stringHasher.putNull()).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putBoolean(true)).andReturn(guavaHasher);
    expect(stringHasher.putBoolean(true)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putBoolean(false)).andReturn(guavaHasher);
    expect(stringHasher.putBoolean(false)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNumber(42)).andReturn(guavaHasher);
    expect(stringHasher.putNumber(42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNumber((long) 42)).andReturn(guavaHasher);
    expect(stringHasher.putNumber((long) 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNumber((short) 42)).andReturn(guavaHasher);
    expect(stringHasher.putNumber((short) 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNumber((byte) 42)).andReturn(guavaHasher);
    expect(stringHasher.putNumber((byte) 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNumber((float) 42)).andReturn(guavaHasher);
    expect(stringHasher.putNumber((float) 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNumber((double) 42)).andReturn(guavaHasher);
    expect(stringHasher.putNumber((double) 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putCharacter((char) 42)).andReturn(guavaHasher);
    expect(stringHasher.putCharacter((char) 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putString("42")).andReturn(guavaHasher);
    expect(stringHasher.putString("42")).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putBytes(BYTE_ARRAY)).andReturn(guavaHasher);
    expect(stringHasher.putBytes(BYTE_ARRAY)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putPattern(PATTERN)).andReturn(guavaHasher);
    expect(stringHasher.putPattern(PATTERN)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c")))
        .andReturn(guavaHasher);
    expect(stringHasher.putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c")))
        .andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putPath(Paths.get("42/42"), HashCode.fromInt(42))).andReturn(guavaHasher);
    expect(stringHasher.putPath(Paths.get("42/42"), HashCode.fromInt(42))).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(
            guavaHasher.putArchiveMemberPath(
                newArchiveMember("42/42", "42/42"), HashCode.fromInt(42)))
        .andReturn(guavaHasher);
    expect(
            stringHasher.putArchiveMemberPath(
                newArchiveMember("42/42", "42/42"), HashCode.fromInt(42)))
        .andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putNonHashingPath("42")).andReturn(guavaHasher);
    expect(stringHasher.putNonHashingPath("42")).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putRuleKey(RULE_KEY_1)).andReturn(guavaHasher);
    expect(stringHasher.putRuleKey(RULE_KEY_1)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putRuleType(RuleType.of("42", RuleType.Kind.BUILD))).andReturn(guavaHasher);
    expect(stringHasher.putRuleType(RuleType.of("42", RuleType.Kind.BUILD)))
        .andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putBuildTarget(TARGET_1)).andReturn(guavaHasher);
    expect(stringHasher.putBuildTarget(TARGET_1)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)))
        .andReturn(guavaHasher);
    expect(stringHasher.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)))
        .andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putContainer(RuleKeyHasher.Container.LIST, 42)).andReturn(guavaHasher);
    expect(stringHasher.putContainer(RuleKeyHasher.Container.LIST, 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putContainer(RuleKeyHasher.Container.MAP, 42)).andReturn(guavaHasher);
    expect(stringHasher.putContainer(RuleKeyHasher.Container.MAP, 42)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.SUPPLIER)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.SUPPLIER)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.APPENDABLE)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.APPENDABLE)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    expect(guavaHasher.putKey("45")).andReturn(guavaHasher);
    expect(stringHasher.putKey("45")).andReturn(stringHasher);
    expect(guavaHasher.putNull()).andReturn(guavaHasher);
    expect(stringHasher.putNull()).andReturn(stringHasher);
    expect(guavaHasher.putBoolean(true)).andReturn(guavaHasher);
    expect(stringHasher.putBoolean(true)).andReturn(stringHasher);
    expect(guavaHasher.putNumber(45)).andReturn(guavaHasher);
    expect(stringHasher.putNumber(45)).andReturn(stringHasher);
    expect(guavaHasher.putString("45")).andReturn(guavaHasher);
    expect(stringHasher.putString("45")).andReturn(stringHasher);
    expect(guavaHasher.putBytes(BYTE_ARRAY)).andReturn(guavaHasher);
    expect(stringHasher.putBytes(BYTE_ARRAY)).andReturn(stringHasher);
    expect(guavaHasher.putPattern(PATTERN)).andReturn(guavaHasher);
    expect(stringHasher.putPattern(PATTERN)).andReturn(stringHasher);
    expect(guavaHasher.putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c")))
        .andReturn(guavaHasher);
    expect(stringHasher.putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c")))
        .andReturn(stringHasher);
    expect(guavaHasher.putPath(Paths.get("45"), HashCode.fromInt(45))).andReturn(guavaHasher);
    expect(stringHasher.putPath(Paths.get("45"), HashCode.fromInt(45))).andReturn(stringHasher);
    expect(guavaHasher.putArchiveMemberPath(newArchiveMember("45", "45"), HashCode.fromInt(45)))
        .andReturn(guavaHasher);
    expect(stringHasher.putArchiveMemberPath(newArchiveMember("45", "45"), HashCode.fromInt(45)))
        .andReturn(stringHasher);
    expect(guavaHasher.putNonHashingPath("45")).andReturn(guavaHasher);
    expect(stringHasher.putNonHashingPath("45")).andReturn(stringHasher);
    expect(guavaHasher.putRuleKey(RULE_KEY_1)).andReturn(guavaHasher);
    expect(stringHasher.putRuleKey(RULE_KEY_1)).andReturn(stringHasher);
    expect(guavaHasher.putRuleType(RuleType.of("45", RuleType.Kind.BUILD))).andReturn(guavaHasher);
    expect(stringHasher.putRuleType(RuleType.of("45", RuleType.Kind.BUILD)))
        .andReturn(stringHasher);
    expect(guavaHasher.putBuildTarget(TARGET_1)).andReturn(guavaHasher);
    expect(stringHasher.putBuildTarget(TARGET_1)).andReturn(stringHasher);
    expect(guavaHasher.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)))
        .andReturn(guavaHasher);
    expect(stringHasher.putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1)))
        .andReturn(stringHasher);
    expect(guavaHasher.putContainer(RuleKeyHasher.Container.LIST, 45)).andReturn(guavaHasher);
    expect(stringHasher.putContainer(RuleKeyHasher.Container.LIST, 45)).andReturn(stringHasher);
    expect(guavaHasher.putContainer(RuleKeyHasher.Container.MAP, 45)).andReturn(guavaHasher);
    expect(stringHasher.putContainer(RuleKeyHasher.Container.MAP, 45)).andReturn(stringHasher);
    expect(guavaHasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL)).andReturn(guavaHasher);
    expect(stringHasher.putWrapper(RuleKeyHasher.Wrapper.OPTIONAL)).andReturn(stringHasher);
    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    replay(stringHasher, guavaHasher);

    newHasher(guavaHasher, stringHasher).hash();
    newHasher(guavaHasher, stringHasher).putKey("42").hash();
    newHasher(guavaHasher, stringHasher).putNull().hash();
    newHasher(guavaHasher, stringHasher).putBoolean(true).hash();
    newHasher(guavaHasher, stringHasher).putBoolean(false).hash();
    newHasher(guavaHasher, stringHasher).putNumber(42).hash();
    newHasher(guavaHasher, stringHasher).putNumber((long) 42).hash();
    newHasher(guavaHasher, stringHasher).putNumber((short) 42).hash();
    newHasher(guavaHasher, stringHasher).putNumber((byte) 42).hash();
    newHasher(guavaHasher, stringHasher).putNumber((float) 42).hash();
    newHasher(guavaHasher, stringHasher).putNumber((double) 42).hash();
    newHasher(guavaHasher, stringHasher).putCharacter((char) 42).hash();
    newHasher(guavaHasher, stringHasher).putString("42").hash();
    newHasher(guavaHasher, stringHasher).putBytes(BYTE_ARRAY).hash();
    newHasher(guavaHasher, stringHasher).putPattern(PATTERN).hash();
    newHasher(guavaHasher, stringHasher)
        .putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"))
        .hash();
    newHasher(guavaHasher, stringHasher).putPath(Paths.get("42/42"), HashCode.fromInt(42)).hash();
    newHasher(guavaHasher, stringHasher)
        .putArchiveMemberPath(newArchiveMember("42/42", "42/42"), HashCode.fromInt(42))
        .hash();
    newHasher(guavaHasher, stringHasher).putNonHashingPath("42").hash();
    newHasher(guavaHasher, stringHasher).putRuleKey(RULE_KEY_1).hash();
    newHasher(guavaHasher, stringHasher).putRuleType(RuleType.of("42", RuleType.Kind.BUILD)).hash();
    newHasher(guavaHasher, stringHasher).putBuildTarget(TARGET_1).hash();
    newHasher(guavaHasher, stringHasher)
        .putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1))
        .hash();
    newHasher(guavaHasher, stringHasher).putContainer(RuleKeyHasher.Container.LIST, 42).hash();
    newHasher(guavaHasher, stringHasher).putContainer(RuleKeyHasher.Container.MAP, 42).hash();
    newHasher(guavaHasher, stringHasher).putWrapper(RuleKeyHasher.Wrapper.SUPPLIER).hash();
    newHasher(guavaHasher, stringHasher).putWrapper(RuleKeyHasher.Wrapper.OPTIONAL).hash();
    newHasher(guavaHasher, stringHasher).putWrapper(RuleKeyHasher.Wrapper.EITHER_LEFT).hash();
    newHasher(guavaHasher, stringHasher).putWrapper(RuleKeyHasher.Wrapper.EITHER_RIGHT).hash();
    newHasher(guavaHasher, stringHasher).putWrapper(RuleKeyHasher.Wrapper.BUILD_RULE).hash();
    newHasher(guavaHasher, stringHasher).putWrapper(RuleKeyHasher.Wrapper.APPENDABLE).hash();

    newHasher(guavaHasher, stringHasher)
        .putKey("45")
        .putNull()
        .putBoolean(true)
        .putNumber(45)
        .putString("45")
        .putBytes(BYTE_ARRAY)
        .putPattern(PATTERN)
        .putSha1(Sha1HashCode.of("a002b39af204cdfaa5fdb67816b13867c32ac52c"))
        .putPath(Paths.get("45"), HashCode.fromInt(45))
        .putArchiveMemberPath(newArchiveMember("45", "45"), HashCode.fromInt(45))
        .putNonHashingPath("45")
        .putRuleKey(RULE_KEY_1)
        .putRuleType(RuleType.of("45", RuleType.Kind.BUILD))
        .putBuildTarget(TARGET_1)
        .putBuildTargetSourcePath(DefaultBuildTargetSourcePath.of(TARGET_1))
        .putContainer(RuleKeyHasher.Container.LIST, 45)
        .putContainer(RuleKeyHasher.Container.MAP, 45)
        .putWrapper(RuleKeyHasher.Wrapper.OPTIONAL)
        .hash();
  }

  @Test
  public void testHashAndOnHash() {
    String string = "hash";
    HashCode hash = createMock(HashCode.class);

    @SuppressWarnings("unchecked")
    RuleKeyHasher<String> stringHasher = createStrictMock(RuleKeyHasher.class);
    @SuppressWarnings("unchecked")
    RuleKeyHasher<HashCode> guavaHasher = createStrictMock(RuleKeyHasher.class);

    expect(guavaHasher.hash()).andReturn(hash);
    expect(stringHasher.hash()).andReturn(string);

    replay(stringHasher, guavaHasher);

    ForwardingRuleKeyHasher<HashCode, String> hasher =
        new ForwardingRuleKeyHasher<HashCode, String>(guavaHasher, stringHasher) {
          @Override
          protected void onHash(HashCode firstHash, String secondHash) {
            assertSame(string, secondHash);
            assertSame(hash, firstHash);
          }
        };
    assertSame(hash, hasher.hash());
  }

  private ArchiveMemberPath newArchiveMember(String archivePath, String memberPath) {
    return ArchiveMemberPath.of(Paths.get(archivePath), Paths.get(memberPath));
  }

  private ForwardingRuleKeyHasher<HashCode, String> newHasher(
      RuleKeyHasher<HashCode> guavaHasher, RuleKeyHasher<String> stringHasher) {
    return new ForwardingRuleKeyHasher<HashCode, String>(guavaHasher, stringHasher) {
      @Override
      protected void onHash(HashCode firstHash, String secondHash) {}
    };
  }
}
