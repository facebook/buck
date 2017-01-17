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

package com.facebook.buck.rules.keys;

import com.facebook.buck.io.ArchiveMemberPath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourceRoot;
import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.base.Joiner;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An implementation of {@link RuleKeyHasher} that serializes to {@link String}.
 */
public class StringRuleKeyHasher implements RuleKeyHasher<String> {

  private final List<String> parts = new ArrayList<>();

  @Override
  public RuleKeyHasher<String> putKey(String key) {
    parts.add(String.format("key(%s)", key));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putNull() {
    parts.add(String.format("null()"));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putBoolean(boolean val) {
    parts.add(String.format("boolean(%s)", val ? "true" : "false"));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putNumber(Number val) {
    parts.add(String.format("number(%s)", val));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putString(CharSequence val) {
    parts.add(String.format("string(\"%s\")", val));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putPattern(Pattern pattern) {
    parts.add(String.format("pattern(%s)", pattern));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putBytes(byte[] bytes) {
    parts.add(String.format("byteArray(length=%s)", bytes.length));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putSha1(Sha1HashCode sha1) {
    parts.add(String.format("sha1(%s)", sha1.toString()));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putArchiveMemberPath(ArchiveMemberPath path, String hash) {
    parts.add(String.format("archiveMember(%s:%s)", path.toString(), hash));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putPath(Path path, String hash) {
    parts.add(String.format("path(%s:%s)", path, hash));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putNonHashingPath(String path) {
    parts.add(String.format("path(%s)", path));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putSourceRoot(SourceRoot sourceRoot) {
    parts.add(String.format("sourceRoot(%s)", sourceRoot.getName()));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putRuleKey(RuleKey ruleKey) {
    parts.add(String.format("ruleKey(sha1=%s)", ruleKey.toString()));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putBuildRuleType(BuildRuleType buildRuleType) {
    parts.add(String.format("ruleType(%s)", buildRuleType.getName()));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putBuildTarget(BuildTarget buildTarget) {
    parts.add(String.format("target(%s)", buildTarget.getFullyQualifiedName()));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putBuildTargetSourcePath(BuildTargetSourcePath targetSourcePath) {
    parts.add(String.format("targetPath(%s)", targetSourcePath.toString()));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putContainer(Container container, int length) {
    parts.add(String.format("container(%s,len=%s)", container, length));
    return this;
  }

  @Override
  public RuleKeyHasher<String> putWrapper(Wrapper wrapper) {
    parts.add(String.format("wrapper(%s)", wrapper));
    return this;
  }

  @Override
  public String hash() {
    parts.add(":");
    return Joiner.on(":").join(parts);
  }
}
