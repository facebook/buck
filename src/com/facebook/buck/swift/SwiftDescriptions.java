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

package com.facebook.buck.swift;

import static com.facebook.buck.swift.SwiftUtil.Constants.SWIFT_EXTENSION;

import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

class SwiftDescriptions {
  /**
   * Utility class: do not instantiate.
   */
  private SwiftDescriptions() {
  }

  static ImmutableSortedSet<SourcePath> filterSwiftSources(
      SourcePathResolver sourcePathResolver, ImmutableSet<SourceWithFlags> srcs) {
    ImmutableSortedSet.Builder<SourcePath> swiftSrcsBuilder = ImmutableSortedSet.naturalOrder();
    for (SourceWithFlags source : srcs) {
      if (MorePaths.getFileExtension(sourcePathResolver.getAbsolutePath(source.getSourcePath()))
          .equalsIgnoreCase(SWIFT_EXTENSION)) {
        swiftSrcsBuilder.add(source.getSourcePath());
      }
    }
    return swiftSrcsBuilder.build();
  }

  static <A extends CxxLibraryDescription.Arg> void populateSwiftLibraryDescriptionArg(
      final SourcePathResolver sourcePathResolver,
      SwiftLibraryDescription.Arg output,
      final A args,
      BuildTarget buildTarget) {
    output.srcs = args.srcs.transform(
        new Function<ImmutableSortedSet<SourceWithFlags>, ImmutableSortedSet<SourcePath>>() {
          @Override
          public ImmutableSortedSet<SourcePath> apply(ImmutableSortedSet<SourceWithFlags> input) {
            return filterSwiftSources(sourcePathResolver, args.srcs.get());
          }
        });
    output.compilerFlags = args.compilerFlags;
    output.frameworks = args.frameworks;
    output.libraries = args.libraries;
    output.deps = args.deps;
    output.supportedPlatformsRegex = args.supportedPlatformsRegex;
    output.moduleName = Optional.of(buildTarget.getShortName());
    output.enableObjcInterop = Optional.of(true);
    output.bridgingHeader = args.bridgingHeader;
  }
}
