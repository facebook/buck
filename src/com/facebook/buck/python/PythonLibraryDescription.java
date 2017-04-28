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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.python.PythonLibraryDescription.Arg;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.coercer.VersionMatchedCollection;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

public class PythonLibraryDescription
    implements Description<Arg>, VersionPropagator<Arg>, MetadataProvidingDescription<Arg> {

  private final FlavorDomain<PythonPlatform> pythonPlatforms;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  private static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("Python Metadata Type", MetadataType.class);

  public PythonLibraryDescription(
      FlavorDomain<PythonPlatform> pythonPlatforms, FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.pythonPlatforms = pythonPlatforms;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Class<Arg> getConstructorArgType() {
    return Arg.class;
  }

  @Override
  public PythonLibrary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      Arg args) {
    return new PythonLibrary(params, resolver);
  }

  @Override
  public <A extends Arg, U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      A args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass)
      throws NoSuchBuildTargetException {

    Optional<Map.Entry<Flavor, MetadataType>> optionalType =
        METADATA_TYPE.getFlavorAndValue(buildTarget);
    if (!optionalType.isPresent()) {
      return Optional.empty();
    }

    Map.Entry<Flavor, MetadataType> type = optionalType.get();

    BuildTarget baseTarget = buildTarget.withoutFlavors(type.getKey());
    switch (type.getValue()) {
      case PACKAGE_COMPONENTS:
        {
          Map.Entry<Flavor, PythonPlatform> pythonPlatform =
              pythonPlatforms
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, CxxPlatform> cxxPlatform =
              cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
          baseTarget = buildTarget.withoutFlavors(pythonPlatform.getKey(), cxxPlatform.getKey());

          SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
          SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
          Path baseModule = PythonUtil.getBasePath(baseTarget, args.baseModule);
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
                      args.srcs,
                      args.platformSrcs,
                      args.versionedSrcs,
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
                      args.resources,
                      args.platformResources,
                      args.versionedResources,
                      selectedVersions),
                  ImmutableMap.of(),
                  ImmutableSet.of(),
                  args.zipSafe);

          return Optional.of(components).map(metadataClass::cast);
        }

      case PACKAGE_DEPS:
        {
          Map.Entry<Flavor, PythonPlatform> pythonPlatform =
              pythonPlatforms
                  .getFlavorAndValue(baseTarget)
                  .orElseThrow(IllegalArgumentException::new);
          Map.Entry<Flavor, CxxPlatform> cxxPlatform =
              cxxPlatforms.getFlavorAndValue(baseTarget).orElseThrow(IllegalArgumentException::new);
          baseTarget = buildTarget.withoutFlavors(pythonPlatform.getKey(), cxxPlatform.getKey());
          ImmutableList<BuildTarget> depTargets =
              PythonUtil.getDeps(
                  pythonPlatform.getValue(), cxxPlatform.getValue(), args.deps, args.platformDeps);
          return Optional.of(resolver.getAllRules(depTargets)).map(metadataClass::cast);
        }
    }

    throw new IllegalStateException();
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

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public SourceList srcs = SourceList.EMPTY;
    public Optional<VersionMatchedCollection<SourceList>> versionedSrcs = Optional.empty();
    public PatternMatchedCollection<SourceList> platformSrcs = PatternMatchedCollection.of();
    public SourceList resources = SourceList.EMPTY;
    public Optional<VersionMatchedCollection<SourceList>> versionedResources;
    public PatternMatchedCollection<SourceList> platformResources = PatternMatchedCollection.of();
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps =
        PatternMatchedCollection.of();
    public Optional<String> baseModule = Optional.empty();
    public Optional<Boolean> zipSafe = Optional.empty();

    @Hint(isDep = false)
    public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }
  }
}
