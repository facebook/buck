/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.coercer.Either;
import com.facebook.buck.model.Pair;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class AppleLibraryDescription implements
    Description<AppleNativeTargetDescriptionArg>, Flavored {
  public static final BuildRuleType TYPE = new BuildRuleType("apple_library");

  private static final Set<Flavor> SUPPORTED_FLAVORS = ImmutableSet.of(
      CompilationDatabase.COMPILATION_DATABASE,
      AbstractAppleNativeTargetBuildRuleDescriptions.HEADERS,
      CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR,
      CxxDescriptionEnhancer.STATIC_FLAVOR,
      CxxDescriptionEnhancer.SHARED_FLAVOR,
      new Flavor("default"));

  private static final Predicate<Flavor> IS_SUPPORTED_FLAVOR = new Predicate<Flavor>() {
    @Override
    public boolean apply(Flavor flavor) {
      return SUPPORTED_FLAVORS.contains(flavor);
    }
  };

  private final AppleConfig appleConfig;
  private final CxxLibraryDescription delegate;

  public AppleLibraryDescription(AppleConfig appleConfig, CxxLibraryDescription delegate) {
    this.appleConfig = appleConfig;
    this.delegate = delegate;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public AppleNativeTargetDescriptionArg createUnpopulatedConstructorArg() {
    return new AppleNativeTargetDescriptionArg();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return FluentIterable.from(flavors).allMatch(IS_SUPPORTED_FLAVOR) ||
        delegate.hasFlavors(flavors);
  }

  @Override
  public <A extends AppleNativeTargetDescriptionArg> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {
    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    TargetSources targetSources = TargetSources.ofAppleSources(pathResolver, args.srcs.get());
    Optional<BuildRule> flavoredRule = AbstractAppleNativeTargetBuildRuleDescriptions
        .createFlavoredRule(
            params,
            resolver,
            args,
            appleConfig,
            pathResolver,
            targetSources);
    if (flavoredRule.isPresent()) {
      return flavoredRule.get();
    }

    CxxLibraryDescription.Arg delegateArg = delegate.createUnpopulatedConstructorArg();
    delegateArg.srcs = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.copyOf(targetSources.srcPaths)));
    delegateArg.headers = Optional.of(
        Either.<ImmutableList<SourcePath>, ImmutableMap<String, SourcePath>>ofLeft(
            ImmutableList.copyOf(targetSources.headerPaths)));
    delegateArg.compilerFlags = Optional.of(ImmutableList.<String>of());
    delegateArg.preprocessorFlags = Optional.of(ImmutableList.<String>of());
    delegateArg.langPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    delegateArg.lexSrcs = Optional.of(ImmutableList.<SourcePath>of());
    delegateArg.yaccSrcs = Optional.of(ImmutableList.<SourcePath>of());
    delegateArg.deps = args.deps;
    delegateArg.headerNamespace = args.headerPathPrefix.or(
        Optional.of(params.getBuildTarget().getShortName()));
    delegateArg.exportedPreprocessorFlags = Optional.of(ImmutableList.<String>of());
    delegateArg.exportedLangPreprocessorFlags = Optional.of(
        ImmutableMap.<CxxSource.Type, ImmutableList<String>>of());
    delegateArg.linkerFlags = Optional.of(ImmutableList.<String>of());
    delegateArg.platformLinkerFlags =
        Optional.of(ImmutableList.<Pair<String, ImmutableList<String>>>of());
    delegateArg.soname = Optional.absent();
    delegateArg.linkWhole = Optional.of(!isSharedLibraryTarget(params.getBuildTarget()));

    return delegate.createBuildRule(params, resolver, delegateArg);
  }

  public static boolean isSharedLibraryTarget(BuildTarget target) {
    return target.getFlavors().contains(CxxDescriptionEnhancer.SHARED_FLAVOR);
  }
}
