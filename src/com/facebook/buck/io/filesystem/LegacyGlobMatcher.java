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

package com.facebook.buck.io.filesystem;

import com.facebook.buck.io.watchman.Capability;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class LegacyGlobMatcher implements com.facebook.buck.io.filesystem.PathMatcher {

  @Override
  public ImmutableList<?> toWatchmanMatchQuery(Set<Capability> capabilities) {
    switch (getType()) {
      case GLOB:
        return globPatternMatcher.get().toWatchmanMatchQuery(capabilities);
      default:
        throw new RuntimeException(String.format("Unsupported type: '%s'", getType()));
    }
  }

  public enum Type {
    GLOB
  }

  private final Type type;
  private final Optional<GlobPatternMatcher> globPatternMatcher;

  public LegacyGlobMatcher(String globPattern) {
    this.type = Type.GLOB;
    this.globPatternMatcher = Optional.of(GlobPatternMatcher.of(globPattern));
  }

  public Type getType() {
    return type;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }

    if (!(other instanceof LegacyGlobMatcher)) {
      return false;
    }

    LegacyGlobMatcher that = (LegacyGlobMatcher) other;

    return Objects.equals(type, that.type)
        && Objects.equals(globPatternMatcher, that.globPatternMatcher);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, globPatternMatcher);
  }

  @Override
  public String toString() {
    return String.format("%s type=%s globPattern=%s", super.toString(), type, globPatternMatcher);
  }

  @Override
  public boolean matches(Path path) {
    switch (type) {
      case GLOB:
        return globPatternMatcher.get().matches(path);
    }
    throw new RuntimeException("Unsupported type " + type);
  }

  private String getGlob() {
    Preconditions.checkState(type == Type.GLOB);
    return globPatternMatcher.get().getGlob();
  }

  @Override
  public String getPathOrGlob() {
    switch (type) {
      case GLOB:
        return getGlob();
    }
    throw new RuntimeException(String.format("Unsupported type: '%s'", type));
  }
}
