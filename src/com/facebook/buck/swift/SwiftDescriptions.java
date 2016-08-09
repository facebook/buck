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

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.apple.ApplePlatforms;
import com.facebook.buck.apple.MultiarchFileInfo;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourceWithFlags;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreStrings;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class SwiftDescriptions {
  /**
   * Utility class: do not instantiate.
   */
  private SwiftDescriptions() {
  }

  public static <T extends CxxConstructorArg> Optional<BuildRule> generateCompanionSwiftBuildRule(
      BuildRuleParams parentParams,
      BuildRuleResolver buildRuleResolver,
      T args,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> platformFlavorsToAppleCxxPlatforms) {
    BuildTarget parentTarget = parentParams.getBuildTarget();
    CxxPlatform cxxPlatform = cxxPlatformFlavorDomain.getValue(parentTarget)
        .or(defaultCxxPlatform);
    // TODO(tho@uber.com) support version in swift flavor.
    BuildTarget swiftCompanionTarget = parentTarget.withFlavors(
        cxxPlatform.getFlavor(),
        Optional.of("Swift").transform(Flavor.TO_FLAVOR).get());
    BuildRuleParams params = parentParams.copyWithBuildTarget(swiftCompanionTarget);

    // check the cache
    Optional<BuildRule> rule = buildRuleResolver.getRuleOptional(swiftCompanionTarget);
    if (rule.isPresent()) {
      return rule;
    }

    if (!args.srcs.isPresent() || args.srcs.get().isEmpty()) {
      return Optional.absent();
    }

    ImmutableSortedSet.Builder<SourcePath> swiftSrcsBuilder = ImmutableSortedSet.naturalOrder();
    for (SourceWithFlags source : args.srcs.get()) {
      if (MoreStrings.endsWithIgnoreCase(source.getSourcePath().toString(), SWIFT_EXTENSION)) {
        swiftSrcsBuilder.add(source.getSourcePath());
      }
    }
    ImmutableSortedSet<SourcePath> swiftSrcs = swiftSrcsBuilder.build();
    if (swiftSrcs.isEmpty()) {
      return Optional.absent();
    }

    AppleCxxPlatform appleCxxPlatform = ApplePlatforms.getAppleCxxPlatformForBuildTarget(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        platformFlavorsToAppleCxxPlatforms,
        params.getBuildTarget(),
        Optional.<MultiarchFileInfo>absent());
    Optional<Tool> swiftCompiler = appleCxxPlatform.getSwift();
    if (!swiftCompiler.isPresent()) {
      throw new HumanReadableException("Platform %s is missing swift compiler", appleCxxPlatform);
    }

    return Optional.<BuildRule>of(new SwiftLibrary(
        swiftCompiler.get(),
        params,
        new SourcePathResolver(buildRuleResolver),
        ImmutableList.<BuildRule>of(),
        args.frameworks.get(),
        args.libraries.get(),
        platformFlavorsToAppleCxxPlatforms,
        BuildTargets.getGenPath(
            params.getProjectFilesystem(),
            swiftCompanionTarget.withAppendedFlavors(cxxPlatform.getFlavor()), "%s"),
        swiftCompanionTarget.getShortName(),
        swiftSrcs));
  }
}
