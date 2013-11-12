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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Utility for reading the metadata associated with a build rule's output. This is metadata that
 * would have been written by a {@link BuildInfoRecorder} when the rule was built initially.
 * <p>
 * Such metadata is stored as key/value pairs.
 */
public class OnDiskBuildInfo {

  private final ProjectFilesystem projectFilesystem;
  private final Path metadataDirectory;

  public OnDiskBuildInfo(BuildTarget target, ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    Preconditions.checkNotNull(target);
    this.metadataDirectory = BuildInfo.getPathToMetadataDirectory(target);
  }

  /**
   * @return the value associated with the specified key, if it exists.
   */
  public Optional<String> getValue(String key) {
    return projectFilesystem.readFileIfItExists(metadataDirectory.resolve(key));
  }

  /**
   * @return Assuming the value associated with the specified key is a valid sha1 hash, returns it
   *     as a {@link Sha1HashCode}, if it exists.
   */
  public Optional<Sha1HashCode> getHash(String key) {
    return getValue(key).transform(Sha1HashCode.TO_SHA1);
  }

  /**
   * Returns the {@link RuleKey} for the rule whose output is currently stored on disk.
   * <p>
   * This value would have been written the last time the rule was built successfully.
   */
  public Optional<RuleKey> getRuleKey() {
    return getValue(BuildInfo.METADATA_KEY_FOR_RULE_KEY).transform(RuleKey.TO_RULE_KEY);
  }

  /**
   * Returns the {@link RuleKey} without deps for the rule whose output is currently stored on disk.
   * <p>
   * This value would have been written the last time the rule was built successfully.
   */
  public Optional<RuleKey> getRuleKeyWithoutDeps() {
    return getValue(BuildInfo.METADATA_KEY_FOR_RULE_KEY_WITHOUT_DEPS)
        .transform(RuleKey.TO_RULE_KEY);
  }

  /**
   * Invokes the {@link Buildable#getPathToOutputFile()} method of the specified {@link Buildable},
   * reads the file at the specified path, and returns the list of lines in the file.
   */
  public List<String> getOutputFileContentsByLine(Buildable buildable) throws IOException {
    Preconditions.checkNotNull(buildable);
    String pathToOutputFile = buildable.getPathToOutputFile();
    Preconditions.checkNotNull(pathToOutputFile);
    Path path = Paths.get(pathToOutputFile);
    return projectFilesystem.readLines(path);
  }
}
