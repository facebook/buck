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
import com.facebook.buck.rules.BinaryWrapperRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasSrcs;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.ToolProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class RustTestDescription
    implements Description<RustTestDescriptionArg>,
        ImplicitDepsInferringDescription<RustTestDescription.AbstractRustTestDescriptionArg>,
        Flavored,
        VersionRoot<RustTestDescriptionArg> {

  private final RustBuckConfig rustBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;
  private final CxxPlatform defaultCxxPlatform;

  public RustTestDescription(
      RustBuckConfig rustBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms,
      CxxPlatform defaultCxxPlatform) {
    this.rustBuckConfig = rustBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
    this.defaultCxxPlatform = defaultCxxPlatform;
  }

  @Override
  public Class<RustTestDescriptionArg> getConstructorArgType() {
    return RustTestDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      RustTestDescriptionArg args)
      throws NoSuchBuildTargetException {
    final BuildTarget buildTarget = params.getBuildTarget();

    BuildTarget exeTarget =
        params.getBuildTarget().withAppendedFlavors(InternalFlavor.of("unittest"));

    Optional<Map.Entry<Flavor, RustBinaryDescription.Type>> type =
        RustBinaryDescription.BINARY_TYPE.getFlavorAndValue(buildTarget);

    boolean isCheck = type.map(t -> t.getValue().isCheck()).orElse(false);

    BinaryWrapperRule testExeBuild =
        resolver.addToIndex(
            RustCompileUtils.createBinaryBuildRule(
                params.withBuildTarget(exeTarget),
                resolver,
                rustBuckConfig,
                cxxPlatforms,
                defaultCxxPlatform,
                args.getCrate(),
                args.getFeatures(),
                Stream.of(
                        args.isFramework() ? Stream.of("--test") : Stream.<String>empty(),
                        rustBuckConfig.getRustTestFlags().stream(),
                        args.getRustcFlags().stream())
                    .flatMap(x -> x)
                    .iterator(),
                args.getLinkerFlags().iterator(),
                RustCompileUtils.getLinkStyle(params.getBuildTarget(), args.getLinkStyle()),
                args.isRpath(),
                args.getSrcs(),
                args.getCrateRoot(),
                ImmutableSet.of("lib.rs", "main.rs"),
                isCheck));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);

    Tool testExe = testExeBuild.getExecutableCommand();

    BuildRuleParams testParams = params.copyAppendingExtraDeps(testExe.getDeps(ruleFinder));

    return new RustTest(testParams, ruleFinder, testExeBuild, args.getLabels(), args.getContacts());
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      AbstractRustTestDescriptionArg constructorArg,
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

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractRustTestDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps, HasSrcs {
    ImmutableSet<String> getContacts();

    @Value.NaturalOrder
    ImmutableSortedSet<String> getFeatures();

    ImmutableList<String> getRustcFlags();

    ImmutableList<String> getLinkerFlags();

    Optional<Linker.LinkableDepType> getLinkStyle();

    @Value.Default
    default boolean isRpath() {
      return true;
    }

    @Value.Default
    default boolean isFramework() {
      return true;
    }

    Optional<String> getCrate();

    Optional<SourcePath> getCrateRoot();
  }
}
