/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.rules.keys.hasher;

import com.facebook.buck.core.io.ArchiveMemberPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.RuleType;
import com.facebook.buck.core.rulekey.RuleKey;
import com.facebook.buck.core.sourcepath.BuildTargetSourcePath;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.hash.HashCode;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * A hasher used for the rule key construction.
 *
 * <p><b>Warning:</b> The result of calling any methods after calling {@link #hash} is undefined.
 *
 * <p>Chunks of data that are put into the {@link RuleKeyHasher} are delimited. Delimiting details
 * are implementation dependent, but one common way is to hash the size of the data along with the
 * data itself, for each operation performed. For example, the following three expressions should
 * ideally all generate different hash codes:
 *
 * <pre>{@code
 * newHasher().putByte(b1).putByte(b2).putByte(b3).hash()
 * newHasher().putByte(b1).putBytes(new byte[] { b2, b3 }).hash()
 * newHasher().putBytes(new byte[] { b1, b2, b3 }).hash()
 * }</pre>
 *
 * Note, Buck hashes both field values and field names (keys) when constructing rule keys. E.g.:
 *
 * <pre>{@code
 * public class myRule implements BuildRule {
 *   @AddToRuleKey
 *   private final int someInt = 42;
 * }
 * }</pre>
 *
 * should have {@code "someInt"} hashed via {@code putKey}, and similarly {@code 42} hashed via
 * {@code putNumber}.
 */
public interface RuleKeyHasher<HASH> {
  enum Container {
    TUPLE,
    LIST,
    MAP,
  }

  enum Wrapper {
    SUPPLIER,
    OPTIONAL,
    OPTIONAL_INT,
    EITHER_LEFT,
    EITHER_RIGHT,
    BUILD_RULE,
    APPENDABLE,
  }

  /** Puts the field's key (i.e. the name of the field) */
  RuleKeyHasher<HASH> putKey(String key);

  /** Puts the field's value, Java types */
  RuleKeyHasher<HASH> putNull();

  RuleKeyHasher<HASH> putCharacter(char val);

  RuleKeyHasher<HASH> putBoolean(boolean val);

  RuleKeyHasher<HASH> putNumber(Number val);

  RuleKeyHasher<HASH> putString(String val);

  RuleKeyHasher<HASH> putBytes(byte[] bytes);

  RuleKeyHasher<HASH> putPattern(Pattern pattern);

  /** Puts the field's value, Buck specific types */
  RuleKeyHasher<HASH> putSha1(Sha1HashCode sha1);

  RuleKeyHasher<HASH> putPath(Path path, HashCode hash);

  RuleKeyHasher<HASH> putArchiveMemberPath(ArchiveMemberPath path, HashCode hash);

  RuleKeyHasher<HASH> putNonHashingPath(String path);

  RuleKeyHasher<HASH> putRuleKey(RuleKey ruleKey);

  RuleKeyHasher<HASH> putRuleType(RuleType ruleType);

  RuleKeyHasher<HASH> putBuildTarget(BuildTarget buildTarget);

  RuleKeyHasher<HASH> putBuildTargetSourcePath(BuildTargetSourcePath buildTargetSourcePath);

  /** Puts the container signature */
  RuleKeyHasher<HASH> putContainer(Container container, int length);

  RuleKeyHasher<HASH> putWrapper(Wrapper wrapper);

  /** Computes the final hash. */
  HASH hash();
}
