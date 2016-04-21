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

import com.facebook.buck.apple.AppleCxxPlatform;
import com.facebook.buck.apple.ApplePlatforms;
import com.facebook.buck.apple.MultiarchFileInfo;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;

public class SwiftDescriptions {
  /** Utility class: do not instantiate. */
  private SwiftDescriptions() {}

  @SuppressWarnings("unused")
  static BuildRule createSwiftModule(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CxxPlatform platform,
      String moduleName,
      ImmutableSortedSet<SourcePath> sources,
      ImmutableList<String> compilerFlags,
      ImmutableSortedSet<FrameworkPath> frameworks,
      ImmutableSortedSet<FrameworkPath> libraries,
      boolean enableObjcInterop) {
    AppleCxxPlatform appleCxxPlatform = ApplePlatforms.getAppleCxxPlatformForBuildTarget(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        appleCxxPlatformFlavorDomain,
        params.getBuildTarget(),
        Optional.<MultiarchFileInfo>absent());
    Optional<Tool> swiftCompiler = appleCxxPlatform.getSwift();
    if (!swiftCompiler.isPresent()) {
      throw new HumanReadableException("Platform %s is missing swift compiler", appleCxxPlatform);
    }
    return new SwiftCompile(
        params,
        new SourcePathResolver(resolver),
        swiftCompiler.get(),
        moduleName,
        BuildTargets.getGenPath(params.getBuildTarget(), "%s"),
        sources);
  }
}
