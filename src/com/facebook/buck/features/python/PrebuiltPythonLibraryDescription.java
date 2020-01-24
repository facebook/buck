/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.python;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class PrebuiltPythonLibraryDescription
    implements DescriptionWithTargetGraph<PrebuiltPythonLibraryDescriptionArg>, Flavored {

  private static final FlavorDomain<PythonLibraryDescription.LibraryType> LIBRARY_TYPE =
      FlavorDomain.from("Python Library Type", PythonLibraryDescription.LibraryType.class);

  private final ToolchainProvider toolchainProvider;

  public PrebuiltPythonLibraryDescription(ToolchainProvider toolchainProvider) {
    this.toolchainProvider = toolchainProvider;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(
        ImmutableSet.of(
            LIBRARY_TYPE,
            getPythonPlatforms(toolchainTargetConfiguration),
            toolchainProvider
                .getByName(
                    CxxPlatformsProvider.DEFAULT_NAME,
                    toolchainTargetConfiguration,
                    CxxPlatformsProvider.class)
                .getUnresolvedCxxPlatforms()));
  }

  private FlavorDomain<PythonPlatform> getPythonPlatforms(
      TargetConfiguration toolchainTargetConfiguration) {
    return toolchainProvider
        .getByName(
            PythonPlatformsProvider.DEFAULT_NAME,
            toolchainTargetConfiguration,
            PythonPlatformsProvider.class)
        .getPythonPlatforms();
  }

  @Override
  public Class<PrebuiltPythonLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltPythonLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltPythonLibraryDescriptionArg args) {

    Optional<Map.Entry<Flavor, PythonLibraryDescription.LibraryType>> optionalType =
        LIBRARY_TYPE.getFlavorAndValue(buildTarget);
    if (optionalType.isPresent()) {
      Map.Entry<Flavor, PythonPlatform> pythonPlatform =
          getPythonPlatforms(buildTarget.getTargetConfiguration())
              .getFlavorAndValue(buildTarget)
              .orElseThrow(IllegalArgumentException::new);
      FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
          toolchainProvider
              .getByName(
                  CxxPlatformsProvider.DEFAULT_NAME,
                  buildTarget.getTargetConfiguration(),
                  CxxPlatformsProvider.class)
              .getUnresolvedCxxPlatforms();
      Map.Entry<Flavor, UnresolvedCxxPlatform> cxxPlatform =
          cxxPlatforms.getFlavorAndValue(buildTarget).orElseThrow(IllegalArgumentException::new);
      BuildTarget baseTarget =
          buildTarget.withoutFlavors(
              optionalType.get().getKey(), pythonPlatform.getKey(), cxxPlatform.getKey());
      PrebuiltPythonLibrary lib =
          (PrebuiltPythonLibrary)
              context.getActionGraphBuilder().requireRule(baseTarget.withAppendedFlavors());
      return PythonCompileRule.from(
          buildTarget,
          context.getProjectFilesystem(),
          context.getActionGraphBuilder(),
          pythonPlatform.getValue().getEnvironment(),
          PrebuiltPythonLibraryComponents.ofSources(lib.getSourcePathToOutput()),
          args.isIgnoreCompileErrors());
    }

    return new PrebuiltPythonLibrary(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        args.getBinarySrc(),
        args.isExcludeDepsFromMergedLinking(),
        args.isCompile());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  /** Ways of building this library. */
  enum LibraryType implements FlavorConvertible {
    /** Compile the sources in this library into bytecode. */
    COMPILE(InternalFlavor.of("compile")),
    ;

    private final Flavor flavor;

    LibraryType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  @RuleArg
  interface AbstractPrebuiltPythonLibraryDescriptionArg extends BuildRuleArg, HasDeclaredDeps {
    SourcePath getBinarySrc();

    @Value.Default
    default boolean isExcludeDepsFromMergedLinking() {
      return false;
    }

    @Value.Default
    default boolean isIgnoreCompileErrors() {
      return false;
    }

    @Value.Default
    default boolean isCompile() {
      return true;
    }
  }
}
