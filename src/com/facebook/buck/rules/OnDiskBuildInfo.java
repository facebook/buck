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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface OnDiskBuildInfo {

  /**
   * @return the value associated with the specified key, if it exists.
   */
  public Optional<String> getValue(String key);

  /**
   * @return the sequence of values associated with the specified key, if it exists.
   */
  public Optional<ImmutableList<String>> getValues(String key);

  /**
   * @return Assuming the value associated with the specified key is a valid sha1 hash, returns it
   *     as a {@link Sha1HashCode}, if it exists.
   */
  public Optional<Sha1HashCode> getHash(String key);

  /**
   * Returns the {@link RuleKey} for the rule whose output is currently stored on disk.
   * <p>
   * This value would have been written the last time the rule was built successfully.
   */
  public Optional<RuleKey> getRuleKey();

  /**
   * Returns the {@link RuleKey} without deps for the rule whose output is currently stored on disk.
   * <p>
   * This value would have been written the last time the rule was built successfully.
   */
  public Optional<RuleKey> getRuleKeyWithoutDeps();

  /**
   * Invokes the {@link Buildable#getPathToOutputFile()} method of the specified {@link Buildable},
   * reads the file at the specified path, and returns the list of lines in the file.
   */
  public List<String> getOutputFileContentsByLine(Buildable buildable) throws IOException;

  public List<String> getOutputFileContentsByLine(Path path) throws IOException;

  /**
   * Sets the executable flag on the given buildable's output file. Used to work around an issue
   * where executable flags aren't preserved when uploading to cache.
   */
  // TODO(task #3321496): Delete this part of the interface after zipping is fixed.
  public void makeOutputFileExecutable(Buildable buildable) throws IOException;

  public void deleteExistingMetadata() throws IOException;
}
