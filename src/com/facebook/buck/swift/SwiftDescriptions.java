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

import static com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable.Linkage.STATIC;
import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPANION_FLAVOR;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.SourceWithFlags;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.io.file.MorePaths;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Optional;

public class SwiftDescriptions {

  static final String SWIFT_HEADER_SUFFIX = "-Swift";
  static final String SWIFT_MAIN_FILENAME = "main.swift";
  public static final String SWIFT_EXTENSION = "swift";

  /** Utility class: do not instantiate. */
  private SwiftDescriptions() {}

  public static boolean isSwiftSource(
      SourceWithFlags source, SourcePathResolver sourcePathResolver) {
    return MorePaths.getFileExtension(sourcePathResolver.getAbsolutePath(source.getSourcePath()))
        .equalsIgnoreCase(SWIFT_EXTENSION);
  }

  static ImmutableSortedSet<SourcePath> filterSwiftSources(
      SourcePathResolver sourcePathResolver, ImmutableSet<SourceWithFlags> srcs) {
    ImmutableSortedSet.Builder<SourcePath> swiftSrcsBuilder = ImmutableSortedSet.naturalOrder();
    for (SourceWithFlags source : srcs) {
      if (isSwiftSource(source, sourcePathResolver)) {
        swiftSrcsBuilder.add(source.getSourcePath());
      }
    }
    return swiftSrcsBuilder.build();
  }

  public static void populateSwiftLibraryDescriptionArg(
      SourcePathResolver sourcePathResolver,
      SwiftLibraryDescriptionArg.Builder output,
      CxxLibraryDescription.CommonArg args,
      BuildTarget buildTarget) {

    output.setName(args.getName());
    output.setSrcs(filterSwiftSources(sourcePathResolver, args.getSrcs()));
    if (args instanceof SwiftCommonArg) {
      output.setCompilerFlags(((SwiftCommonArg) args).getSwiftCompilerFlags());
      output.setVersion(((SwiftCommonArg) args).getSwiftVersion());
    } else {
      output.setCompilerFlags(args.getCompilerFlags());
    }
    output.setFrameworks(args.getFrameworks());
    output.setLibraries(args.getLibraries());
    output.setDeps(args.getDeps());
    output.setSupportedPlatformsRegex(args.getSupportedPlatformsRegex());
    output.setModuleName(
        args.getModuleName().map(Optional::of).orElse(Optional.of(buildTarget.getShortName())));
    output.setEnableObjcInterop(true);
    output.setBridgingHeader(args.getBridgingHeader());

    boolean isCompanionTarget = buildTarget.getFlavors().contains(SWIFT_COMPANION_FLAVOR);
    output.setPreferredLinkage(
        isCompanionTarget ? Optional.of(STATIC) : args.getPreferredLinkage());
  }

  static String toSwiftHeaderName(String moduleName) {
    return moduleName + SWIFT_HEADER_SUFFIX;
  }
}
