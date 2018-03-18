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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.HeaderMode;
import com.facebook.buck.cxx.toolchain.HeaderSymlinkTree;
import com.facebook.buck.cxx.toolchain.HeaderVisibility;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorConvertible;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.ImplicitFlavorsInferringDescription;
import com.facebook.buck.rules.MetadataProvidingDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.coercer.SourceList;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.toolchain.ToolchainProvider;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.Version;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Sets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.immutables.value.Value;

public class CxxLibraryDescription
    implements Description<CxxLibraryDescriptionArg>,
        ImplicitDepsInferringDescription<CxxLibraryDescription.CommonArg>,
        ImplicitFlavorsInferringDescription,
        Flavored,
        MetadataProvidingDescription<CxxLibraryDescriptionArg>,
        VersionPropagator<CxxLibraryDescriptionArg> {

  public enum Type implements FlavorConvertible {
    HEADERS(CxxDescriptionEnhancer.HEADER_SYMLINK_TREE_FLAVOR),
    EXPORTED_HEADERS(CxxDescriptionEnhancer.EXPORTED_HEADER_SYMLINK_TREE_FLAVOR),
    SANDBOX_TREE(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR),
    SHARED(CxxDescriptionEnhancer.SHARED_FLAVOR),
    SHARED_INTERFACE(InternalFlavor.of("shared-interface")),
    STATIC_PIC(CxxDescriptionEnhancer.STATIC_PIC_FLAVOR),
    STATIC(CxxDescriptionEnhancer.STATIC_FLAVOR),
    MACH_O_BUNDLE(CxxDescriptionEnhancer.MACH_O_BUNDLE_FLAVOR),
    ;

    private final Flavor flavor;

    Type(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  static final FlavorDomain<Type> LIBRARY_TYPE =
      FlavorDomain.from("C/C++ Library Type", Type.class);

  public enum MetadataType implements FlavorConvertible {
    CXX_HEADERS(InternalFlavor.of("header-symlink-tree")),
    CXX_PREPROCESSOR_INPUT(InternalFlavor.of("cxx-preprocessor-input")),
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

  public static final FlavorDomain<MetadataType> METADATA_TYPE =
      FlavorDomain.from("C/C++ Metadata Type", MetadataType.class);

  static final FlavorDomain<HeaderVisibility> HEADER_VISIBILITY =
      FlavorDomain.from("C/C++ Header Visibility", HeaderVisibility.class);

  static final FlavorDomain<HeaderMode> HEADER_MODE =
      FlavorDomain.from("C/C++ Header Mode", HeaderMode.class);

  private final ToolchainProvider toolchainProvider;
  private final CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors;
  private final CxxLibraryFlavored cxxLibraryFlavored;
  private final CxxLibraryFactory cxxLibraryFactory;
  private final CxxLibraryMetadataFactory cxxLibraryMetadataFactory;

  public CxxLibraryDescription(
      ToolchainProvider toolchainProvider,
      CxxLibraryImplicitFlavors cxxLibraryImplicitFlavors,
      CxxLibraryFlavored cxxLibraryFlavored,
      CxxLibraryFactory cxxLibraryFactory,
      CxxLibraryMetadataFactory cxxLibraryMetadataFactory) {
    this.toolchainProvider = toolchainProvider;
    this.cxxLibraryImplicitFlavors = cxxLibraryImplicitFlavors;
    this.cxxLibraryFlavored = cxxLibraryFlavored;
    this.cxxLibraryFactory = cxxLibraryFactory;
    this.cxxLibraryMetadataFactory = cxxLibraryMetadataFactory;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return cxxLibraryFlavored.flavorDomains();
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> flavors) {
    return cxxLibraryFlavored.hasFlavors(flavors);
  }

  /**
   * This function is broken out so that CxxInferEnhancer can get a list of dependencies for
   * building the library.
   */
  static ImmutableList<CxxPreprocessorInput> getPreprocessorInputsForBuildingLibrarySources(
      CxxBuckConfig cxxBuckConfig,
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      BuildTarget target,
      CommonArg args,
      CxxPlatform cxxPlatform,
      ImmutableSet<BuildRule> deps,
      TransitiveCxxPreprocessorInputFunction transitivePreprocessorInputs,
      ImmutableList<HeaderSymlinkTree> headerSymlinkTrees,
      Optional<SymlinkTree> sandboxTree) {
    return CxxDescriptionEnhancer.collectCxxPreprocessorInput(
        target,
        cxxPlatform,
        ruleResolver,
        deps,
        ImmutableListMultimap.copyOf(
            Multimaps.transformValues(
                CxxFlags.getLanguageFlagsWithMacros(
                    args.getPreprocessorFlags(),
                    args.getPlatformPreprocessorFlags(),
                    args.getLangPreprocessorFlags(),
                    cxxPlatform),
                f ->
                    CxxDescriptionEnhancer.toStringWithMacrosArgs(
                        target, cellRoots, ruleResolver, cxxPlatform, f))),
        headerSymlinkTrees,
        ImmutableSet.of(),
        RichStream.from(
                transitivePreprocessorInputs.apply(
                    target,
                    ruleResolver,
                    cxxPlatform,
                    deps,
                    // Also add private deps if we are _not_ reexporting all deps.
                    args.isReexportAllHeaderDependencies()
                            .orElse(cxxBuckConfig.getDefaultReexportAllHeaderDependencies())
                        ? CxxDeps.of()
                        : args.getPrivateCxxDeps()))
            .toOnceIterable(),
        args.getIncludeDirs(),
        sandboxTree,
        args.getRawHeaders());
  }

  @Override
  public Class<CxxLibraryDescriptionArg> getConstructorArgType() {
    return CxxLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxLibraryDescriptionArg args) {
    return cxxLibraryFactory.createBuildRule(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        context.getBuildRuleResolver(),
        context.getCellPathResolver(),
        args,
        args.getLinkStyle(),
        Optional.empty(),
        ImmutableSet.of(),
        ImmutableSortedSet.of(),
        TransitiveCxxPreprocessorInputFunction.fromLibraryRule(),
        Optional.empty());
  }

  public static Optional<Map.Entry<Flavor, Type>> getLibType(BuildTarget buildTarget) {
    return LIBRARY_TYPE.getFlavorAndValue(buildTarget);
  }

  static BuildTarget getUntypedBuildTarget(BuildTarget buildTarget) {
    Optional<Map.Entry<Flavor, Type>> type = getLibType(buildTarget);
    if (!type.isPresent()) {
      return buildTarget;
    }
    Set<Flavor> flavors = Sets.newHashSet(buildTarget.getFlavors());
    flavors.remove(type.get().getKey());
    BuildTarget target = buildTarget.withFlavors(flavors);
    return target;
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      CommonArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Get any parse time deps from the C/C++ platforms.
    targetGraphOnlyDepsBuilder.addAll(
        CxxPlatforms.getParseTimeDeps(getCxxPlatformsProvider().getCxxPlatforms().getValues()));
  }

  /**
   * Convenience function to query the {@link CxxPreprocessorInput} metadata of a target.
   *
   * <p>Use this function instead of constructing the BuildTarget manually.
   */
  public static Optional<CxxPreprocessorInput> queryMetadataCxxPreprocessorInput(
      BuildRuleResolver resolver,
      BuildTarget baseTarget,
      CxxPlatform platform,
      HeaderVisibility visibility) {
    return resolver.requireMetadata(
        baseTarget.withAppendedFlavors(
            MetadataType.CXX_PREPROCESSOR_INPUT.getFlavor(),
            platform.getFlavor(),
            visibility.getFlavor()),
        CxxPreprocessorInput.class);
  }

  @Override
  public <U> Optional<U> createMetadata(
      BuildTarget buildTarget,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      CxxLibraryDescriptionArg args,
      Optional<ImmutableMap<BuildTarget, Version>> selectedVersions,
      Class<U> metadataClass) {
    return cxxLibraryMetadataFactory.createMetadata(
        buildTarget, resolver, cellRoots, args, metadataClass);
  }

  @Override
  public ImmutableSortedSet<Flavor> addImplicitFlavors(
      ImmutableSortedSet<Flavor> argDefaultFlavors) {
    return cxxLibraryImplicitFlavors.addImplicitFlavorsForRuleTypes(
        argDefaultFlavors, Description.getBuildRuleType(this));
  }

  private CxxPlatformsProvider getCxxPlatformsProvider() {
    return toolchainProvider.getByName(
        CxxPlatformsProvider.DEFAULT_NAME, CxxPlatformsProvider.class);
  }

  /**
   * This is a hack to allow fine grained control over how the transitive {@code
   * CxxPreprocessorInput}s are found. Since not all {@code Description}s which use {@code
   * CxxLibraryDescription} generate a {@code CxxLibrary}, blinding attempting to require it will
   * not work.
   *
   * <p>Therefore for those other rules, we create the list from scratch.
   */
  @FunctionalInterface
  public interface TransitiveCxxPreprocessorInputFunction {
    Stream<CxxPreprocessorInput> apply(
        BuildTarget target,
        BuildRuleResolver ruleResolver,
        CxxPlatform cxxPlatform,
        ImmutableSet<BuildRule> deps,
        CxxDeps privateDeps);

    /**
     * Retrieve the transitive CxxPreprocessorInput from the CxxLibrary rule.
     *
     * <p>This is used by CxxLibrary and AppleLibrary. Rules that do not generate a CxxLibrary rule
     * (namely AppleTest) cannot use this.
     */
    static TransitiveCxxPreprocessorInputFunction fromLibraryRule() {
      return (target, ruleResolver, cxxPlatform, ignored, privateDeps) -> {
        BuildTarget rawTarget =
            target.withoutFlavors(
                ImmutableSet.<Flavor>builder()
                    .addAll(LIBRARY_TYPE.getFlavors())
                    .add(cxxPlatform.getFlavor())
                    .build());
        BuildRule rawRule = ruleResolver.requireRule(rawTarget);
        CxxLibrary rule = (CxxLibrary) rawRule;
        ImmutableMap<BuildTarget, CxxPreprocessorInput> inputs =
            rule.getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver);

        ImmutableList<CxxPreprocessorDep> privateDepsForPlatform =
            RichStream.from(privateDeps.get(ruleResolver, cxxPlatform))
                .filter(CxxPreprocessorDep.class)
                .toImmutableList();
        if (privateDepsForPlatform.isEmpty()) {
          // Nothing to add.
          return inputs.values().stream();
        } else {
          Map<BuildTarget, CxxPreprocessorInput> result = new LinkedHashMap<>();
          result.putAll(inputs);
          for (CxxPreprocessorDep dep : privateDepsForPlatform) {
            result.putAll(dep.getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver));
          }
          return result.values().stream();
        }
      };
    }

    /**
     * Retrieve the transitive {@link CxxPreprocessorInput} from an explicitly specified deps list.
     *
     * <p>This is used by AppleTest, which doesn't generate a CxxLibrary rule that computes this.
     */
    static TransitiveCxxPreprocessorInputFunction fromDeps() {
      return (target, ruleResolver, cxxPlatform, deps, privateDeps) -> {
        Map<BuildTarget, CxxPreprocessorInput> input = new LinkedHashMap<>();
        input.put(
            target,
            queryMetadataCxxPreprocessorInput(
                    ruleResolver, target, cxxPlatform, HeaderVisibility.PUBLIC)
                .orElseThrow(IllegalStateException::new));
        for (BuildRule rule : deps) {
          if (rule instanceof CxxPreprocessorDep) {
            input.putAll(
                ((CxxPreprocessorDep) rule)
                    .getTransitiveCxxPreprocessorInput(cxxPlatform, ruleResolver));
          }
        }
        return input.values().stream();
      };
    }
  }

  public interface CommonArg extends LinkableCxxConstructorArg {
    @Value.Default
    default SourceList getExportedHeaders() {
      return SourceList.EMPTY;
    }

    @Value.Check
    @Override
    default void checkHeadersUsage() {
      LinkableCxxConstructorArg.super.checkHeadersUsage();

      if (getRawHeaders().isEmpty()) {
        return;
      }

      if (!getExportedHeaders().isEmpty()) {
        throw new HumanReadableException(
            "Cannot use `exported_headers` and `raw_headers` in the same rule.");
      }

      if (!getExportedPlatformHeaders().getPatternsAndValues().isEmpty()) {
        throw new HumanReadableException(
            "Cannot use `exported_platform_headers` and `raw_headers` in the same rule.");
      }
    }

    @Value.Default
    default PatternMatchedCollection<SourceList> getExportedPlatformHeaders() {
      return PatternMatchedCollection.of();
    }

    ImmutableList<StringWithMacros> getExportedPreprocessorFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>>
        getExportedPlatformPreprocessorFlags() {
      return PatternMatchedCollection.of();
    }

    ImmutableMap<CxxSource.Type, ImmutableList<StringWithMacros>>
        getExportedLangPreprocessorFlags();

    ImmutableList<StringWithMacros> getExportedLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>>
        getExportedPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    @Value.NaturalOrder
    ImmutableSortedSet<BuildTarget> getExportedDeps();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getExportedPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<Pattern> getSupportedPlatformsRegex();

    Optional<String> getSoname();

    Optional<String> getStaticLibraryBasename();

    Optional<Boolean> getForceStatic();

    Optional<Boolean> getLinkWhole();

    Optional<Boolean> getCanBeAsset();

    Optional<NativeLinkable.Linkage> getPreferredLinkage();

    Optional<Boolean> getXcodePublicHeadersSymlinks();

    Optional<Boolean> getXcodePrivateHeadersSymlinks();

    /**
     * extra_xcode_sources will add the files to the list of files to be compiled in the Xcode
     * target.
     */
    ImmutableList<SourcePath> getExtraXcodeSources();

    /**
     * extra_xcode_sources will add the files to the list of files in the project and won't add them
     * to an Xcode target.
     */
    ImmutableList<SourcePath> getExtraXcodeFiles();

    /**
     * Controls whether the headers of dependencies in "deps" is re-exported for compiling targets
     * that depend on this one.
     */
    Optional<Boolean> isReexportAllHeaderDependencies();

    /**
     * These fields are passed through to SwiftLibrary for mixed C/Swift targets; they are not used
     * otherwise.
     */
    Optional<SourcePath> getBridgingHeader();

    Optional<String> getModuleName();

    /** @return C/C++ deps which are propagated to dependents. */
    @Value.Derived
    default CxxDeps getExportedCxxDeps() {
      return CxxDeps.builder()
          .addDeps(getExportedDeps())
          .addPlatformDeps(getExportedPlatformDeps())
          .build();
    }

    /**
     * Override parent class's deps to include exported deps.
     *
     * @return the C/C++ deps this rule builds against.
     */
    @Override
    @Value.Derived
    default CxxDeps getCxxDeps() {
      return CxxDeps.concat(getPrivateCxxDeps(), getExportedCxxDeps());
    }
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxLibraryDescriptionArg extends CommonArg {}
}
