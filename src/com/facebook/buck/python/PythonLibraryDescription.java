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

package com.facebook.buck.python;

import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.python.toolchain.PythonPlatform;
import com.facebook.buck.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.HasTests;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class PythonLibraryDescription
    implements Description<PythonLibraryDescriptionArg>,
        VersionPropagator<PythonLibraryDescriptionArg>,
        MetadataProvidingDescription<PythonLibraryDescriptionArg> {

  private final ToolchainProvider toolchainProvider;

  private static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("Python Metadata Type", MetadataType.class);

  public PythonLibraryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Class<PythonLibraryDescriptionArg> getConstructorArgType() {
    return PythonLibraryDescriptionArg.class;
  }

  @Override
  public PythonLibrary createBuildRule(
      TargetGraph targetGraph,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      PythonLibraryDescriptionArg args) {
    return new PythonLibrary(buildTarget, projectFilesystem, params, resolver);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      PythonLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {

    Optional<Map.Entry<Flavor, MetadataType>> optionalType =
        METADATA_TYPE.getFlavorAndValue(buildTarget);
    if (!optionalType.isPresent()) {
      return Optional.empty();
    }

    FlavorDomain<CxxPlatform> cxxPlatforms =
        toolchainProvider
            .getByName(CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class)
            .getCxxPlatforms();

    Map.Entry<Flavor, MetadataType> type = optionalType.get();

    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());
    switch (type.getValue()) {
      case PACKAGE_COMPONENTS:
        {
          Map.Entry<Flavor, PythonPlatform> pythonPlatform =
              getPythonPlatforms()
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, CxxPlatform> cxxPlatform =
              cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
          baseTarget = buildTarget.withoutFlavors(pythonPlatform.getKey(), cxxPlatform.getKey());

          SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
          SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
          Path baseModule = PythonUtil.getBasePath(baseTarget, args.getBaseModule());
          PythonPackageComponents components =
              PythonPackageComponents.of(
                  PythonUtil.getModules(
                      baseTarget,
                      resolver,
                      ruleFinder,
                      pathResolver,
                      pythonPlatform.getValue(),
                      cxxPlatform.getValue(),
                      "srcs",
                      baseModule,
                      args.getSrcs(),
                      args.getPlatformSrcs(),
                      args.getVersionedSrcs(),
                      selectedVersions),
                  PythonUtil.getModules(
                      baseTarget,
                      resolver,
                      ruleFinder,
                      pathResolver,
                      pythonPlatform.getValue(),
                      cxxPlatform.getValue(),
                      "resources",
                      baseModule,
                      args.getResources(),
                      args.getPlatformResources(),
                      args.getVersionedResources(),
                      selectedVersions),
                  ImmutableMap.of(),
                  ImmutableSet.of(),
                  args.getZipSafe());

          return Optional.of(components).map(metadataClass::cast);
        }

      case PACKAGE_DEPS:
        {
          Map.Entry<Flavor, PythonPlatform> pythonPlatform =
              getPythonPlatforms()
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, CxxPlatform> cxxPlatform =
              cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
          baseTarget = buildTarget.withoutFlavors(pythonPlatform.getKey(), cxxPlatform.getKey());
          ImmutableList<BuildTarget> depTargets =
              PythonUtil.getDeps(
                  pythonPlatform.getValue(),
                  cxxPlatform.getValue(),
                  args.getDeps(),
                  args.getPlatformDeps());
          return Optional.of(resolver.getAllRules(depTargets)).map(metadataClass::cast);
        }
    }

    throw new IllegalStateException();
  }

  private FlavorDomain<PythonPlatform> getPythonPlatforms() {
    return toolchainProvider
        .getByName(PythonPlatformsProvider.DEFAULT_NAME, PythonPlatformsProvider.class)
        .getPythonPlatforms();
  }

  enum MetadataType implements FlavorConvertible {
    PACKAGE_COMPONENTS(InternalFlavor.of("package-components")),
    PACKAGE_DEPS(InternalFlavor.of("package-deps")),
    ;

    private final Flavor flavor;

    MetadataType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  interface CoreArg extends CommonDescriptionArg, HasDeclaredDeps, HasTests {
    @Value.Default
    default SourceList getSrcs() {
      return SourceList.EMPTY;
    }

    Optional<VersionMatchedCollection<SourceList>> getVersionedSrcs();

    @Value.Default
    default PatternMatchedCollection<SourceList> getPlatformSrcs() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default SourceList getResources() {
      return SourceList.EMPTY;
    }

    Optional<VersionMatchedCollection<SourceList>> getVersionedResources();

    @Value.Default
    default PatternMatchedCollection<SourceList> getPlatformResources() {
      return PatternMatchedCollection.of();
    }

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getBaseModule();

    Optional<Boolean> getZipSafe();
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPythonLibraryDescriptionArg extends CoreArg {}
}
