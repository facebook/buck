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

import com.facebook.buck.util.sha1.Sha1HashCode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FakeOnDiskBuildInfo implements OnDiskBuildInfo {

  private Map<String, String> metadata = new HashMap<>();
  private Map<String, ImmutableList<String>> metadataValues = new HashMap<>();
  private Map<Path, ImmutableList<String>> pathsToContents = new HashMap<>();

  @Override
  public Optional<RuleKey> getRuleKey(String key) {
    return getBuildValue(key).map(RuleKey::new);
  }

  /** @return this */
  public FakeOnDiskBuildInfo putMetadata(String key, String value) {
    this.metadata.put(key, value);
    return this;
  }

  public FakeOnDiskBuildInfo putMetadata(String key, ImmutableList<String> value) {
    this.metadataValues.put(key, value);
    return this;
  }

  @Override
  public Optional<String> getValue(String key) {
    return Optional.ofNullable(metadata.get(key));
  }

  @Override
  public Optional<String> getBuildValue(String key) {
    return Optional.ofNullable(metadata.get(key));
  }

  @Override
  public Optional<ImmutableList<String>> getValues(String key) {
    return Optional.ofNullable(metadataValues.get(key));
  }

  @Override
  public ImmutableList<String> getValuesOrThrow(String key) {
    return Optional.ofNullable(metadataValues.get(key)).get();
  }

  @Override
  public Optional<ImmutableMap<String, String>> getBuildMap(String key) {
    return Optional.empty();
  }

  @Override
  public Optional<Sha1HashCode> getHash(String key) {
    return getValue(key).map(Sha1HashCode::of);
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
  public void deleteExistingMetadata() throws IOException {
    throw new UnsupportedOperationException();
  }
}
