/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.io.watchman.Capability;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/** Matcher that matches file paths with specific extension. */
public class FileExtensionMatcher implements PathMatcher {

  private final String extension;

  private FileExtensionMatcher(String extension) {
    this.extension = extension;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof FileExtensionMatcher)) {
      return false;
    }
    FileExtensionMatcher that = (FileExtensionMatcher) other;
    return Objects.equals(extension, that.extension);
  }

  @Override
  public int hashCode() {
    return Objects.hash(extension);
  }

  @Override
  public String toString() {
    return String.format("%s extension=%s", super.toString(), extension);
  }

  @Override
  public boolean matches(Path path) {
    return extension.equals(Files.getFileExtension(path.toString()));
  }

  private String getGlob() {
    return "**/*." + extension;
  }

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    String ignoreGlob = getGlob();
    return ImmutableList.of(
        "match", ignoreGlob, "wholename", ImmutableMap.of("includedotfiles", true));
  }

  @Override
  public String getPathOrGlob() {
    return getGlob();
  }

  /** @return The matcher for paths that have {@code extension} as an extension. */
  public static FileExtensionMatcher of(String extension) {
    return new FileExtensionMatcher(extension);
  }
}
