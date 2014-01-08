/*
 * Copyright 2013-present Facebook, Inc.
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

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

public class FakeOnDiskBuildInfo implements OnDiskBuildInfo {

  @Nullable private RuleKey ruleKey;
  @Nullable private RuleKey ruleKeyWithoutDeps;
  private Map<String, String> metadata = Maps.newHashMap();
  private Map<Buildable, ImmutableList<String>> outputFileContents = Maps.newHashMap();
  private Map<Path, ImmutableList<String>> pathsToContents = Maps.newHashMap();

  /** @return this */
  public FakeOnDiskBuildInfo setRuleKey(@Nullable RuleKey ruleKey) {
    this.ruleKey = ruleKey;
    return this;
  }

  @Override
  public Optional<RuleKey> getRuleKey() {
    return Optional.fromNullable(ruleKey);
  }

  /** @return this */
  public FakeOnDiskBuildInfo setRuleKeyWithoutDeps(@Nullable RuleKey ruleKeyWithoutDeps) {
    this.ruleKeyWithoutDeps = ruleKeyWithoutDeps;
    return this;
  }

  @Override
  public Optional<RuleKey> getRuleKeyWithoutDeps() {
    return Optional.fromNullable(ruleKeyWithoutDeps);
  }

  /** @return this */
  public FakeOnDiskBuildInfo putMetadata(String key, String value) {
    this.metadata.put(key, value);
    return this;
  }

  @Override
  public Optional<String> getValue(String key) {
    return Optional.fromNullable(metadata.get(key));
  }

  @Override
  public Optional<ImmutableList<String>> getValues(String key) {
    // TODO(mbolin): Implement.
    throw new UnsupportedOperationException();
  }

  @Override
  public Optional<Sha1HashCode> getHash(String key) {
    return getValue(key).transform(Sha1HashCode.TO_SHA1);
  }

  /** @return this */
  public FakeOnDiskBuildInfo setOutputFileContentsForBuildable(Buildable buildable,
      List<String> lines) {
    outputFileContents.put(buildable, ImmutableList.copyOf(lines));
    return this;
  }

  @Override
  public List<String> getOutputFileContentsByLine(Buildable buildable) throws IOException {
    ImmutableList<String> lines = outputFileContents.get(buildable);
    if (lines != null) {
      return lines;
    } else if (buildable.getPathToOutputFile() != null) {
      return getOutputFileContentsByLine(buildable.getPathToOutputFile());
    } else {
      throw new RuntimeException("No lines for buildable: " + buildable);
    }
  }

  /** @return this */
  public FakeOnDiskBuildInfo setFileContentsForPath(Path path, List<String> lines) {
    pathsToContents.put(path, ImmutableList.copyOf(lines));
    return this;
  }

  @Override
  public List<String> getOutputFileContentsByLine(Path path) throws IOException {
    ImmutableList<String> lines = pathsToContents.get(path);
    if (lines != null) {
      return lines;
    } else {
      throw new RuntimeException("No lines for path: " + path);
    }
  }

  @Override
  public void makeOutputFileExecutable(Buildable buildable) {
    throw new UnsupportedOperationException();
  }
}
