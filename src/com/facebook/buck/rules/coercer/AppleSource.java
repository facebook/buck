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
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

/**
 * Simple type representing an iOS or OS X source entry, which can be either:
 *
 *   <ul>
 *     <li>A {@link SourcePath}, or</li>
 *     <li>A {@link SourcePath} plus compiler flags, or</li>
 *     <li>A "source group" (a group name and one or more AppleSource objects)</li>
 *   </ul>
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class AppleSource {
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
  }

  /**
   * Gets the type of source entry this object represents.
   */
  @Value.Parameter
  public abstract Type getType();

  @Value.Parameter
  protected abstract Optional<SourcePath> getSourcePathOptional();

  @Value.Parameter
  protected abstract Optional<Pair<SourcePath, String>> getSourcePathWithFlagsOptional();

  @Value.Parameter
  protected abstract Optional<Pair<String, ImmutableList<AppleSource>>> getSourceGroupOptional();

  @Value.Check
  protected void check() {
    switch (getType()) {
      case SOURCE_PATH:
        Preconditions.checkArgument(getSourcePathOptional().isPresent());
        Preconditions.checkArgument(!getSourcePathWithFlagsOptional().isPresent());
        Preconditions.checkArgument(!getSourceGroupOptional().isPresent());
        break;
      case SOURCE_PATH_WITH_FLAGS:
        Preconditions.checkArgument(!getSourcePathOptional().isPresent());
        Preconditions.checkArgument(getSourcePathWithFlagsOptional().isPresent());
        Preconditions.checkArgument(!getSourceGroupOptional().isPresent());
        break;
      default:
        throw new IllegalArgumentException("Unrecognized type: " + getType());
    }
  }

  /**
   * If getType() returns SOURCE_PATH, returns the source path this entry represents.
   * Otherwise, raises an exception.
   */
  public SourcePath getSourcePath() {
    return getSourcePathOptional().get();
  }

  /**
   * If getType() returns SOURCE_PATH_WITH_FLAGS, returns the (source
   * path, flags) pair this entry represents.  Otherwise, raises an
   * exception.
   */
  public Pair<SourcePath, String> getSourcePathWithFlags() {
    return getSourcePathWithFlagsOptional().get();
  }

  /**
   * If getType() returns SOURCE_GROUP, returns the source group this
   * entry represents. Otherwise, raises an exception.
   */
  public Pair<String, ImmutableList<AppleSource>> getSourceGroup() {
    return getSourceGroupOptional().get();
  }

  /**
   * Creates an {@link AppleSource} given a {@link SourcePath}.
   */
  public static AppleSource ofSourcePath(SourcePath sourcePath) {
    return ImmutableAppleSource.builder()
        .setType(Type.SOURCE_PATH)
        .setSourcePathOptional(sourcePath)
        .build();
  }

  /**
   * Creates an {@link AppleSource} given a ({@link SourcePath}, flags) pair.
   */
  public static AppleSource ofSourcePathWithFlags(Pair<SourcePath, String> sourcePathWithFlags) {
    return ImmutableAppleSource.builder()
        .setType(Type.SOURCE_PATH_WITH_FLAGS)
        .setSourcePathWithFlagsOptional(sourcePathWithFlags)
        .build();
  }

}
