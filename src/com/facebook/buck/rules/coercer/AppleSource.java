/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.model.Pair;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * Simple type representing an iOS or OS X source entry, which can be either:
 *
 *   <ul>
 *     <li>A {@link SourcePath}, or</li>
 *     <li>A {@link SourcePath} plus compiler flags, or</li>
 *     <li>A "source group" (a group name and one or more AppleSource objects)</li>
 *   </ul>
 */
public class AppleSource {
  /**
   * The type of source entry this object represents.
   */
  public enum Type {
      /**
       * A single {@link SourcePath}.
       */
      SOURCE_PATH,
      /**
       * A {@link SourcePath} with compiler flags.
       */
      SOURCE_PATH_WITH_FLAGS,
      /**
       * A source group (group name and one or more AppleSource objects).
       */
      SOURCE_GROUP
  };

  private final Type type;
  @Nullable private final SourcePath sourcePath;
  @Nullable private final Pair<SourcePath, String> sourcePathWithFlags;
  @Nullable private final Pair<String, ImmutableList<AppleSource>> sourceGroup;

  private AppleSource(
      Type type,
      @Nullable SourcePath sourcePath,
      @Nullable Pair<SourcePath, String> sourcePathWithFlags,
      @Nullable Pair<String, ImmutableList<AppleSource>> sourceGroup) {
    this.type = type;
    switch (type) {
      case SOURCE_PATH:
        Preconditions.checkNotNull(sourcePath);
        Preconditions.checkArgument(sourcePathWithFlags == null);
        Preconditions.checkArgument(sourceGroup == null);
        break;
      case SOURCE_PATH_WITH_FLAGS:
        Preconditions.checkArgument(sourcePath == null);
        Preconditions.checkNotNull(sourcePathWithFlags);
        Preconditions.checkArgument(sourceGroup == null);
        break;
      case SOURCE_GROUP:
        Preconditions.checkArgument(sourcePath == null);
        Preconditions.checkArgument(sourcePathWithFlags == null);
        Preconditions.checkNotNull(sourceGroup);
        break;
      default:
        throw new IllegalArgumentException("Unrecognized type: " + type);
    }
    this.sourcePath = sourcePath;
    this.sourcePathWithFlags = sourcePathWithFlags;
    this.sourceGroup = sourceGroup;
  }

  /**
   * Gets the type of source entry this object represents.
   */
  public Type getType() {
    return type;
  }

  /**
   * If getType() returns SOURCE_PATH, returns the source path this entry represents.
   * Otherwise, raises an exception.
   */
  public SourcePath getSourcePath() {
    return Preconditions.checkNotNull(sourcePath);
  }

  /**
   * If getType() returns SOURCE_PATH_WITH_FLAGS, returns the (source
   * path, flags) pair this entry represents.  Otherwise, raises an
   * exception.
   */
  public Pair<SourcePath, String> getSourcePathWithFlags() {
    return Preconditions.checkNotNull(sourcePathWithFlags);
  }

  /**
   * If getType() returns SOURCE_GROUP, returns the source group this
   * entry represents. Otherwise, raises an exception.
   */
  public Pair<String, ImmutableList<AppleSource>> getSourceGroup() {
    return Preconditions.checkNotNull(sourceGroup);
  }

  /**
   * Creates an {@link AppleSource} given a {@link SourcePath}.
   */
  public static AppleSource ofSourcePath(SourcePath sourcePath) {
    return new AppleSource(Type.SOURCE_PATH, sourcePath, null, null);
  }

  /**
   * Creates an {@link AppleSource} given a ({@link SourcePath}, flags) pair.
   */
  public static AppleSource ofSourcePathWithFlags(Pair<SourcePath, String> sourcePathWithFlags) {
    return new AppleSource(Type.SOURCE_PATH_WITH_FLAGS, null, sourcePathWithFlags, null);
  }

  /**
   * Creates an {@link AppleSource} given a (group name, [source1, source2, ...]) pair.
   */
  public static AppleSource ofSourceGroup(Pair<String, ImmutableList<AppleSource>> sourceGroup) {
    return new AppleSource(Type.SOURCE_GROUP, null, null, sourceGroup);
  }

  @Override
  public boolean equals(Object other) {
    if (other instanceof AppleSource) {
      AppleSource that = (AppleSource) other;
      return Objects.equals(this.type, that.type) &&
          Objects.equals(this.sourcePath, that.sourcePath) &&
          Objects.equals(this.sourcePathWithFlags, that.sourcePathWithFlags) &&
          Objects.equals(this.sourceGroup, that.sourceGroup);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, sourcePath, sourcePathWithFlags, sourceGroup);
  }
}
