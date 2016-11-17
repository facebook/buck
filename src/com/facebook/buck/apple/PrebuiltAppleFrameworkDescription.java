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
package com.facebook.buck.apple;

import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.FrameworkDependencies;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Optional;
import java.util.regex.Pattern;

public class PrebuiltAppleFrameworkDescription implements
    Description<PrebuiltAppleFrameworkDescription.Arg>,
    MetadataProvidingDescription<PrebuiltAppleFrameworkDescription.Arg> {

  public static final BuildRuleType TYPE = BuildRuleType.of("prebuilt_apple_framework");

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public PrebuiltAppleFrameworkDescription.Arg createUnpopulatedConstructorArg() {
    return new PrebuiltAppleFrameworkDescription.Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver resolver,
      final A args) throws NoSuchBuildTargetException {
    return new PrebuiltAppleFramework(
        params,
        resolver,
        new SourcePathResolver(resolver),
        args.framework,
        args.frameworks,
        args.supportedPlatformsRegex,
        input -> CxxFlags.getFlags(
            args.exportedLinkerFlags,
            args.exportedPlatformLinkerFlags,
            input)
    );
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Class<U> metadataClass) throws NoSuchBuildTargetException {
    if (metadataClass.isAssignableFrom(FrameworkDependencies.class)) {
      resolver.requireRule(buildTarget);
      ImmutableSet<BuildTargetSourcePath> sourcePaths =
          ImmutableSet.of(new BuildTargetSourcePath(buildTarget));
      return Optional.of(metadataClass.cast(FrameworkDependencies.of(sourcePaths)));
    }
    return Optional.empty();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public SourcePath framework;
    public ImmutableSortedSet<FrameworkPath> frameworks = ImmutableSortedSet.of();
    public Optional<Pattern> supportedPlatformsRegex;
    public ImmutableList<String> exportedLinkerFlags = ImmutableList.of();
    public PatternMatchedCollection<ImmutableList<String>> exportedPlatformLinkerFlags =
        PatternMatchedCollection.of();
  }
}
