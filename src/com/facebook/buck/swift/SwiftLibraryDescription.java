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
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

public class SwiftLibraryDescription implements
    Description<SwiftLibraryDescription.Arg>,
    Flavored {
  public static final BuildRuleType TYPE = BuildRuleType.of("swift_library");

  private final FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain;
  private final FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain;
  private final CxxPlatform defaultCxxPlatform;

  public SwiftLibraryDescription(
      FlavorDomain<CxxPlatform> cxxPlatformFlavorDomain,
      FlavorDomain<AppleCxxPlatform> appleCxxPlatformFlavorDomain,
      CxxPlatform defaultCxxPlatform) {
    this.cxxPlatformFlavorDomain = cxxPlatformFlavorDomain;
    this.appleCxxPlatformFlavorDomain = appleCxxPlatformFlavorDomain;
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public SwiftLibraryDescription.Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxPlatformFlavorDomain.containsAnyOf(flavors);
  }

  @Override
  public <A extends SwiftLibraryDescription.Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) throws NoSuchBuildTargetException {
    // See if we're building a particular "type" and "platform" of this library, and if so, extract
    // them from the flavors attached to the build target.
    BuildTarget buildTarget = params.getBuildTarget();

    AppleCxxPlatform appleCxxPlatform = ApplePlatforms.getAppleCxxPlatformForBuildTarget(
        cxxPlatformFlavorDomain,
        defaultCxxPlatform,
        appleCxxPlatformFlavorDomain,
        buildTarget,
        Optional.<MultiarchFileInfo>absent());
    Optional<Tool> swiftCompiler = appleCxxPlatform.getSwift();
    if (!swiftCompiler.isPresent()) {
      throw new HumanReadableException("Platform %s is missing swift compiler", appleCxxPlatform);
    }

    CxxPlatform cxxPlatform = cxxPlatformFlavorDomain.getValue(buildTarget)
        .or(defaultCxxPlatform);

    // Otherwise, we return the generic placeholder of this library.
    return new SwiftLibrary(
        swiftCompiler.get(),
        params,
        new SourcePathResolver(resolver),
        ImmutableList.<BuildRule>of(),
        args.frameworks.get(),
        args.libraries.get(),
        appleCxxPlatformFlavorDomain,
        BuildTargets.getGenPath(
            params.getProjectFilesystem(),
            buildTarget.withFlavors(cxxPlatform.getFlavor()), "%s"),
        args.moduleName.or(buildTarget.getShortName()),
        args.srcs.get());
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public Optional<String> moduleName;
    public Optional<ImmutableSortedSet<SourcePath>> srcs;
    public Optional<ImmutableList<String>> compilerFlags;
    public Optional<ImmutableSortedSet<FrameworkPath>> frameworks;
    public Optional<ImmutableSortedSet<FrameworkPath>> libraries;
    public Optional<Boolean> enableObjcInterop;
  }

}
