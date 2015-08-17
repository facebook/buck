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

import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Frameworks can be specified as either a path to a file, or a path prefixed by a build setting.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractFrameworkPath implements Comparable<AbstractFrameworkPath> {
  /**
   * The type of framework entry this object represents.
   */
  protected enum Type {
    /**
     * An Xcode style {@link SourceTreePath}.
     */
    SOURCE_TREE_PATH,
    /**
     * A Buck style {@link SourcePath}.
     */
    SOURCE_PATH,
  }

  @Value.Parameter
  protected abstract Type getType();

  @Value.Parameter
  public abstract Optional<SourceTreePath> getSourceTreePath();

  @Value.Parameter
  public abstract Optional<SourcePath> getSourcePath();

  public Path getFileName(Function<SourcePath, Path> resolver) {
    switch (getType()) {
      case SOURCE_TREE_PATH:
        return getSourceTreePath().get().getPath().getFileName();
      case SOURCE_PATH:
        return Preconditions
            .checkNotNull(resolver.apply(getSourcePath().get()))
            .getFileName();
      default:
        throw new RuntimeException("Unhandled type: " + getType());
    }
  }

  public String getName(Function<SourcePath, Path> resolver) {
    String fileName = getFileName(resolver).toString();
    return Files.getNameWithoutExtension(fileName);
  }

  public static Function<FrameworkPath, Path> getUnexpandedSearchPathFunction(
      final Function<SourcePath, Path> resolver,
      final Function<? super Path, Path> relativizer) {
    return new Function<FrameworkPath, Path>() {
      @Override
      public Path apply(FrameworkPath input) {
        return getConvertToPathFunction(resolver, relativizer).apply(input).getParent();
      }
    };
  }

  public static Function<FrameworkPath, Path> getConvertToPathFunction(
      final Function<SourcePath, Path> resolver,
      final Function<? super Path, Path> relativizer) {
    return new Function<FrameworkPath, Path>() {
      @Override
      public Path apply(FrameworkPath input) {
        switch (input.getType()) {
          case SOURCE_TREE_PATH:
            return Paths.get(input.getSourceTreePath().get().toString());
          case SOURCE_PATH:
            return relativizer.apply(
                Preconditions
                    .checkNotNull(resolver.apply(input.getSourcePath().get())));
          default:
            throw new RuntimeException("Unhandled type: " + input.getType());
        }
      }
    };
  }

  public static Function<FrameworkPath, Path> getExpandedSearchPathFunction(
      final Function<SourcePath, Path> sourcePathResolver,
      final Function<SourceTreePath, Path> sourceTreePathResolver) {
    return new Function<FrameworkPath, Path>() {
      @Override
      public Path apply(FrameworkPath input) {
        return getExpandFunction(
            sourcePathResolver,
            sourceTreePathResolver
        ).apply(input).getParent();
      }
    };
  }

  public static Function<FrameworkPath, Path> getExpandFunction(
      final Function<SourcePath, Path> sourcePathResolver,
      final Function<SourceTreePath, Path> sourceTreePathResolver) {
    return new Function<FrameworkPath, Path>() {
      @Override
      public Path apply(FrameworkPath input) {
        switch (input.getType()) {
          case SOURCE_TREE_PATH:
            return Preconditions.checkNotNull(
                sourceTreePathResolver.apply(input.getSourceTreePath().get()));
          case SOURCE_PATH:
            return Preconditions
                .checkNotNull(sourcePathResolver.apply(input.getSourcePath().get()));
          default:
            throw new RuntimeException("Unhandled type: " + input.getType());
        }
      }
    };
  }

  @Value.Check
  protected void check() {
    switch (getType()) {
      case SOURCE_TREE_PATH:
        Preconditions.checkArgument(getSourceTreePath().isPresent());
        Preconditions.checkArgument(!getSourcePath().isPresent());
        break;
      case SOURCE_PATH:
        Preconditions.checkArgument(!getSourceTreePath().isPresent());
        Preconditions.checkArgument(getSourcePath().isPresent());
        break;
      default:
        throw new RuntimeException("Unhandled type: " + getType());
    }
  }

  @Override
  public int compareTo(AbstractFrameworkPath o) {
    int typeComparisonResult = getType().ordinal() - o.getType().ordinal();
    if (typeComparisonResult != 0) {
      return typeComparisonResult;
    }
    switch (getType()) {
      case SOURCE_TREE_PATH:
        return getSourceTreePath().get().compareTo(o.getSourceTreePath().get());
      case SOURCE_PATH:
        return getSourcePath().get().compareTo(o.getSourcePath().get());
      default:
        throw new RuntimeException("Unhandled type: " + getType());
    }
  }

  public static FrameworkPath ofSourceTreePath(SourceTreePath sourceTreePath) {
    return FrameworkPath.of(
        Type.SOURCE_TREE_PATH,
        Optional.of(sourceTreePath),
        Optional.<SourcePath>absent());
  }

  public static FrameworkPath ofSourcePath(SourcePath sourcePath) {
    return FrameworkPath.of(
        Type.SOURCE_PATH,
        Optional.<SourceTreePath>absent(),
        Optional.of(sourcePath));
  }
}
