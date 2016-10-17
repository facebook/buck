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

import static com.facebook.buck.cxx.NativeLinkable.Linkage.STATIC;
import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPANION_FLAVOR;
import static com.facebook.buck.swift.SwiftUtil.Constants.SWIFT_EXTENSION;

import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;

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
    output.srcs = filterSwiftSources(sourcePathResolver, args.srcs);
    output.headersSearchPath = FluentIterable.from(args.exportedHeaders.getPaths())
        .uniqueIndex(path -> {
          Preconditions.checkArgument(path instanceof PathSourcePath);
          return ((PathSourcePath) path).getRelativePath();
        });
    output.compilerFlags = args.compilerFlags;
    output.frameworks = args.frameworks;
    output.libraries = args.libraries;
    output.deps = args.deps;
    output.supportedPlatformsRegex = args.supportedPlatformsRegex;
    output.moduleName =
        args.moduleName.map(Optional::of).orElse(Optional.of(buildTarget.getShortName()));
    output.enableObjcInterop = Optional.of(true);
    output.bridgingHeader = args.bridgingHeader;

    boolean isCompanionTarget = buildTarget.getFlavors().contains(SWIFT_COMPANION_FLAVOR);
    output.preferredLinkage = isCompanionTarget ? Optional.of(STATIC) : args.preferredLinkage;
  }

}
