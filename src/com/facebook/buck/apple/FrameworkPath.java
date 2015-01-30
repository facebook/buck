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

package com.facebook.buck.apple;

import com.facebook.buck.apple.xcode.xcodeproj.PBXReference;
import com.facebook.buck.apple.xcode.xcodeproj.SourceTreePath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Frameworks can be specified as either a path to a file, or a path prefixed by a build setting.
 */
@Value.Immutable
@BuckStyleImmutable
public abstract class FrameworkPath {
  public abstract Optional<SourceTreePath> getSourceTreePath();
  public abstract Optional<SourcePath> getSourcePath();

  @Value.Check
  protected void check() {
    Preconditions.checkState(
        (getSourceTreePath().isPresent() || getSourcePath().isPresent()) &&
            !(getSourceTreePath().isPresent() && getSourcePath().isPresent()),
        "Exactly one of sourceTreePath or sourcePath should be set");
  }

  public static FrameworkPath fromString(BuildTarget target, String string) {
    Path path = Paths.get(string);

    String firstElement =
        Preconditions.checkNotNull(Iterables.getFirst(path, Paths.get(""))).toString();

    if (firstElement.startsWith("$")) { // NOPMD - length() > 0 && charAt(0) == '$' is ridiculous
      Optional<PBXReference.SourceTree> sourceTree =
          PBXReference.SourceTree.fromBuildSetting(firstElement);
      if (sourceTree.isPresent()) {
        return ImmutableFrameworkPath.builder()
            .setSourceTreePath(
                new SourceTreePath(sourceTree.get(), path.subpath(1, path.getNameCount())))
            .build();
      } else {
        throw new HumanReadableException(String.format(
            "Unknown SourceTree: %s in target: %s. Should be one of: %s",
            firstElement,
            target,
            Joiner.on(',').join(
                Iterables.transform(
                    ImmutableList.copyOf(PBXReference.SourceTree.values()),
                    new Function<PBXReference.SourceTree, String>() {
                      @Override
                      public String apply(PBXReference.SourceTree input) {
                        return "$" + input.toString();
                      }
                    }))));
      }
    } else {
      return ImmutableFrameworkPath.builder()
          .setSourcePath(new BuildTargetSourcePath(target, Paths.get(string)))
          .build();
    }
  }

  public static Function<String, FrameworkPath> transformFromString(final BuildTarget target) {
    return new Function<String, FrameworkPath>() {
      @Override
      public FrameworkPath apply(String input) {
        return fromString(target, input);
      }
    };
  }
}
