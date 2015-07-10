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
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
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

  public enum FrameworkType {
    /**
     * The path refers to a framework.
     */
    FRAMEWORK,
    /**
     * The path refers to a library.
     */
    LIBRARY,
    ;

    public static Optional<FrameworkType> fromExtension(String extension) {
      return Optional.fromNullable(
          Functions.forMap(
              ImmutableMap.of(
                  "framework", FRAMEWORK,
                  "dylib", LIBRARY,
                  "a", LIBRARY))
              .apply(extension));
    }
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
    String nameWithoutExtension = Files.getNameWithoutExtension(fileName);
    FrameworkType frameworkType = getFrameworkType(resolver);

    switch (frameworkType) {
      case FRAMEWORK:
        return nameWithoutExtension;
      case LIBRARY:
        String libraryPrefix = "lib";
        if (!fileName.startsWith(libraryPrefix)) {
          throw new HumanReadableException("Unsupported library prefix: " + fileName);
        }
        return nameWithoutExtension.substring(
            libraryPrefix.length(),
            nameWithoutExtension.length());
      default:
        throw new RuntimeException("Unhandled framework type: " + frameworkType);
    }
  }

  public FrameworkType getFrameworkType(Function<SourcePath, Path> resolver) {
    Path fileName = getFileName(resolver);
    Optional<FrameworkType> frameworkType = FrameworkType.fromExtension(
        Files.getFileExtension(fileName.toString()));

    if (!frameworkType.isPresent()) {
      throw new HumanReadableException("Unsupported framework file name: " + fileName);
    }

    return frameworkType.get();
  }

  public static Function<FrameworkPath, FrameworkType> getFrameworkTypeFunction(
      final Function<SourcePath, Path> resolver) {
    return new Function<FrameworkPath, FrameworkType>() {
      @Override
      public FrameworkType apply(FrameworkPath input) {
        return input.getFrameworkType(resolver);
      }
    };
  }

  public static Function<FrameworkPath, Path> getUnexpandedSearchPathFunction(
      final Function<SourcePath, Path> resolver,
      final Function<? super Path, Path> relativizer) {
    return new Function<FrameworkPath, Path>() {
      @Override
      public Path apply(FrameworkPath input) {
        switch (input.getType()) {
          case SOURCE_TREE_PATH:
            return Paths.get(input.getSourceTreePath().get().toString()).getParent();
          case SOURCE_PATH:
            return relativizer.apply(
                Preconditions
                    .checkNotNull(resolver.apply(input.getSourcePath().get()))
                    .getParent());
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
        switch (input.getType()) {
          case SOURCE_TREE_PATH:
            return Preconditions.checkNotNull(
                sourceTreePathResolver.apply(input.getSourceTreePath().get()))
                .getParent();
          case SOURCE_PATH:
            return Preconditions
                .checkNotNull(sourcePathResolver.apply(input.getSourcePath().get()))
                .getParent();
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
