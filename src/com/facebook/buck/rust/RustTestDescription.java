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

package com.facebook.buck.rust;

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BinaryWrapperRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.versions.VersionRoot;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class RustTestDescription implements
    Description<RustTestDescription.Arg>,
    ImplicitDepsInferringDescription<RustTestDescription.Arg>,
    Flavored,
    VersionRoot<RustTestDescription.Arg> {

  private final RustBuckConfig rustBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;


  public RustTestDescription(
      RustBuckConfig rustBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms, CxxPlatform defaultCxxPlatform) {
    this.rustBuckConfig = rustBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    final BuildTarget buildTarget = params.getBuildTarget();

    BuildTarget exeTarget = params.getBuildTarget()
        .withAppendedFlavors(InternalFlavor.of("unittest"));

    Optional<Map.Entry<Flavor, RustBinaryDescription.Type>> type =
        RustBinaryDescription.BINARY_TYPE.getFlavorAndValue(buildTarget);

    boolean isCheck = type.map(t -> t.getValue().isCheck()).orElse(false);

    BinaryWrapperRule testExeBuild = resolver.addToIndex(
        RustCompileUtils.createBinaryBuildRule(
            params.withBuildTarget(exeTarget),
            resolver,
            rustBuckConfig,
            cxxPlatforms,
            defaultCxxPlatform,
            args.crate,
            args.features,
            Stream.of(
                args.framework ? Stream.of("--test") : Stream.<String>empty(),
                rustBuckConfig.getRustTestFlags().stream(),
                args.rustcFlags.stream())
                .flatMap(x -> x).iterator(),
            args.linkerFlags.iterator(),
            RustCompileUtils.getLinkStyle(params.getBuildTarget(), args.linkStyle),
            args.rpath, args.srcs,
            args.crateRoot,
            ImmutableSet.of("lib.rs", "main.rs"),
            isCheck
        ));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    Tool testExe = testExeBuild.getExecutableCommand();

    BuildRuleParams testParams = params.copyAppendingExtraDeps(
        testExe.getDeps(ruleFinder));

    return new RustTest(
        testParams,
        ruleFinder,
        testExeBuild,
        args.labels,
        args.contacts);
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    ToolProvider compiler = rustBuckConfig.getRustCompiler();
    extraDepsBuilder.addAll(compiler.getParseTimeDeps());

    extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms.getValues()));
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    if (cxxPlatforms.containsAnyOf(flavors)) {
      return true;
    }

    for (RustBinaryDescription.Type type : RustBinaryDescription.Type.values()) {
      if (flavors.contains(type.getFlavor())) {
        return true;
      }
    }

    return false;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return Optional.of(ImmutableSet.of(cxxPlatforms, RustBinaryDescription.BINARY_TYPE));
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg {
    public ImmutableSortedSet<SourcePath> srcs = ImmutableSortedSet.of();
    public ImmutableSet<String> contacts = ImmutableSet.of();
    public ImmutableSortedSet<String> features = ImmutableSortedSet.of();
    public ImmutableList<String> rustcFlags = ImmutableList.of();
    public ImmutableList<String> linkerFlags = ImmutableList.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public Optional<Linker.LinkableDepType> linkStyle;
    public boolean rpath = true;
    public boolean framework = true;
    public Optional<String> crate;
    public Optional<SourcePath> crateRoot;
  }
}
