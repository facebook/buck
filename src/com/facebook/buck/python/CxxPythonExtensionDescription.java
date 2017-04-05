/*
 * Copyright 2014-present Facebook, Inc.
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

import com.facebook.buck.cxx.CxxBuckConfig;
import com.facebook.buck.cxx.CxxConstructorArg;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxFlags;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.CxxPlatforms;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.HeaderSymlinkTree;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.NativeLinkTarget;
import com.facebook.buck.cxx.NativeLinkTargetMode;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.Flavored;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.versions.VersionPropagator;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public class CxxPythonExtensionDescription implements
    Description<CxxPythonExtensionDescription.Arg>,
    Flavored,
    ImplicitDepsInferringDescription<CxxPythonExtensionDescription.Arg>,
    VersionPropagator<CxxPythonExtensionDescription.Arg> {

  private enum Type {
    EXTENSION,
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.EXTENSION));

  private final FlavorDomain<PythonPlatform> pythonPlatforms;
  private final CxxBuckConfig cxxBuckConfig;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public CxxPythonExtensionDescription(
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxBuckConfig cxxBuckConfig,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.pythonPlatforms = pythonPlatforms;
    this.cxxBuckConfig = cxxBuckConfig;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains() {
    return
        Optional.of(
            ImmutableSet.of(
                pythonPlatforms,
                cxxPlatforms
            )
        );
  }

  @Override
  public boolean hasFlavors(ImmutableSet<Flavor> inputFlavors) {
    Set<Flavor> flavors = inputFlavors;
    flavors = Sets.difference(flavors, cxxPlatforms.getFlavors());
    flavors = Sets.difference(flavors, pythonPlatforms.getFlavors());
    flavors = Sets.difference(
        flavors,
        ImmutableSet.of(
            CxxDescriptionEnhancer.SHARED_FLAVOR
        ));
    return flavors.isEmpty();
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  static BuildTarget getExtensionTarget(
      BuildTarget target,
      Flavor pythonPlatform,
      Flavor platform) {
    return CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
        BuildTarget.builder(target)
            .addFlavors(pythonPlatform)
            .build(),
        platform,
        Linker.LinkType.SHARED);
  }

  @VisibleForTesting
  static String getExtensionName(
      String moduleName,
      Optional<PythonBuildConfig> pythonBuildConfig) {
    return moduleName +
        pythonBuildConfig.map(c -> c.getExtensionSuffix()).orElse(".so");
  }

  private Path getExtensionPath(
      ProjectFilesystem filesystem,
      BuildTarget target,
      String moduleName,
      Flavor pythonPlatform,
      Flavor platform) {
    return BuildTargets
        .getGenPath(filesystem, getExtensionTarget(target, pythonPlatform, platform), "%s")
        .resolve(
            getExtensionName(
                moduleName,
                pythonPlatforms.getValue(pythonPlatform).getEnvironment().getPythonBuildConfig()));
  }

  private ImmutableList<com.facebook.buck.rules.args.Arg> getExtensionArgs(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder,
      CellPathResolver cellRoots,
      CxxPlatform cxxPlatform,
      Arg args,
      ImmutableSet<BuildRule> deps,
      Optional<PythonBuildConfig> pythonBuildConfig) throws NoSuchBuildTargetException {

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            ruleResolver,
            ruleFinder,
            pathResolver,
            cxxPlatform,
            args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            ruleResolver,
            ruleFinder,
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE,
            true);
    Optional<SymlinkTree> sandboxTree = Optional.empty();
    if (cxxBuckConfig.sandboxSources()) {
      sandboxTree =
          CxxDescriptionEnhancer.createSandboxTree(
              params,
              ruleResolver,
              cxxPlatform);
    }

    ImmutableList.Builder<String> preprocessorFlagsBuilder = ImmutableList.builder();
    if (pythonBuildConfig.isPresent()) {
      preprocessorFlagsBuilder.addAll(pythonBuildConfig.get().getPreprocessorFlags());
    }
    preprocessorFlagsBuilder.addAll(args.preprocessorFlags);
    ImmutableList<String> preprocessorFlags = preprocessorFlagsBuilder.build();

    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            deps,
            CxxFlags.getLanguageFlags(
                preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(cxxPlatform, deps),
            args.includeDirs,
            sandboxTree);

    ImmutableList.Builder<String> compileFlagsBuilder = ImmutableList.builder();
    if (pythonBuildConfig.isPresent()) {
      compileFlagsBuilder.addAll(pythonBuildConfig.get().getCompilerFlags());
    }
    compileFlagsBuilder.addAll(args.compilerFlags);

    // Generate rule to build the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            ruleResolver,
            pathResolver,
            ruleFinder,
            cxxBuckConfig,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getLanguageFlags(
                compileFlagsBuilder.build(),
                args.platformCompilerFlags,
                args.langCompilerFlags,
                cxxPlatform),
            args.prefixHeader,
            args.precompiledHeader,
            srcs,
            CxxSourceRuleFactory.PicType.PIC,
            sandboxTree);

    ImmutableList.Builder<com.facebook.buck.rules.args.Arg> argsBuilder = ImmutableList.builder();
    argsBuilder.addAll(
        CxxDescriptionEnhancer.toStringWithMacrosArgs(
            params.getBuildTarget(),
            cellRoots,
            ruleResolver,
            cxxPlatform,
            CxxFlags.getFlagsWithMacrosWithPlatformMacroExpansion(
                args.linkerFlags,
                args.platformLinkerFlags,
                cxxPlatform)));

    // Embed a origin-relative library path into the binary so it can find the shared libraries.
    argsBuilder.addAll(
        StringArg.from(
            Linkers.iXlinker(
                "-rpath",
                String.format("%s/", cxxPlatform.getLd().resolve(ruleResolver).libOrigin()))));

    // Add object files into the args.
    argsBuilder.addAll(SourcePathArg.from(picObjects.values()));

    if (pythonBuildConfig.isPresent()) {
      argsBuilder.addAll(StringArg.from(pythonBuildConfig.get().getLinkerFlags()));
    }

    return argsBuilder.build();
  }

  private ImmutableSet<BuildRule> getPlatformDeps(
      BuildRuleResolver ruleResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      Arg args) {

    ImmutableSet.Builder<BuildRule> rules = ImmutableSet.builder();

    // Add declared deps.
    rules.addAll(args.getCxxDeps().get(ruleResolver, cxxPlatform));

    // Add platform specific deps.
    rules.addAll(
        ruleResolver.getAllRules(
            Iterables.concat(
                args.platformDeps.getMatchingValues(pythonPlatform.getFlavor().toString()))));

    // Add a dep on the python C/C++ library.
    if (pythonPlatform.getCxxLibrary().isPresent()) {
      rules.add(ruleResolver.getRule(pythonPlatform.getCxxLibrary().get()));
    }

    return rules.build();
  }


  private <A extends Arg> BuildRule createExtensionBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      A args)
      throws NoSuchBuildTargetException {
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    String moduleName = args.moduleName.orElse(params.getBuildTarget().getShortName());
    String extensionName = getExtensionName(
        moduleName,
        pythonPlatform.getEnvironment().getPythonBuildConfig());
    Path extensionPath =
        getExtensionPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            moduleName,
            pythonPlatform.getFlavor(),
            cxxPlatform.getFlavor());
    ImmutableSet<BuildRule> deps = getPlatformDeps(ruleResolver, pythonPlatform, cxxPlatform, args);
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxBuckConfig,
        cxxPlatform,
        params,
        ruleResolver,
        pathResolver,
        ruleFinder,
        getExtensionTarget(
            params.getBuildTarget(),
            pythonPlatform.getFlavor(),
            cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        Linker.LinkableDepType.SHARED,
        /* thinLto */ false,
        RichStream.from(deps)
            .filter(NativeLinkable.class)
            .toImmutableList(),
        args.cxxRuntimeType,
        Optional.empty(),
        ImmutableSet.of(),
        NativeLinkableInput.builder()
        .setArgs(
            getExtensionArgs(
                params
                    .withBuildTarget(
                        params.getBuildTarget().withoutFlavors(
                            LinkerMapMode.FLAVOR_DOMAIN.getFlavors())),
                ruleResolver,
                pathResolver,
                ruleFinder,
                cellRoots,
                cxxPlatform,
                args,
                deps,
                getPythonBuildConfig(pythonPlatform)))
          .setFrameworks(args.frameworks)
          .setLibraries(args.libraries)
          .build());
  }

  private static final Optional<PythonBuildConfig> getPythonBuildConfig(
      PythonPlatform pythonPlatform) {
    if (pythonPlatform.getFlavor().equals(PythonBuckConfig.DEFAULT_PYTHON_PLATFORM)) {
      // We only use the detected Python build config for the
      // py-default flavor.  Any other flavors have already specified
      // the Python build config through other means in .buckconfig.
      return pythonPlatform.getEnvironment().getPythonBuildConfig();
    } else {
      return Optional.empty();
    }
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver ruleResolver,
      CellPathResolver cellRoots,
      final A args) throws NoSuchBuildTargetException {

    Optional<Map.Entry<Flavor, CxxPlatform>> platform = cxxPlatforms.getFlavorAndValue(
        params.getBuildTarget());
    if (params.getBuildTarget().getFlavors().contains(CxxDescriptionEnhancer.SANDBOX_TREE_FLAVOR)) {
      return CxxDescriptionEnhancer.createSandboxTreeBuildRule(
          ruleResolver,
          args,
          platform.get().getValue(),
          params);
    }
    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    final Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(
        params.getBuildTarget());
    final Optional<Map.Entry<Flavor, PythonPlatform>> pythonPlatform =
        pythonPlatforms.getFlavorAndValue(params.getBuildTarget());

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent() && platform.isPresent() && pythonPlatform.isPresent()) {
      Preconditions.checkState(type.get().getValue() == Type.EXTENSION);
      return createExtensionBuildRule(
          params,
          ruleResolver,
          cellRoots,
          pythonPlatform.get().getValue(),
          platform.get().getValue(),
          args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);
    final Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    String moduleName = args.moduleName.orElse(params.getBuildTarget().getShortName());
    return new CxxPythonExtension(params) {

      @Override
      protected BuildRule getExtension(
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        return ruleResolver.requireRule(
            getBuildTarget().withAppendedFlavors(
                pythonPlatform.getFlavor(),
                cxxPlatform.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR));
      }

      @Override
      public Path getModule(PythonPlatform pythonPlatform) {
        return baseModule.resolve(
            getExtensionName(
                moduleName,
                getPythonBuildConfig(pythonPlatform)));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        BuildRule extension = getExtension(pythonPlatform, cxxPlatform);
        SourcePath output = extension.getSourcePathToOutput();
        return PythonPackageComponents.of(
            ImmutableMap.of(getModule(pythonPlatform), output),
            ImmutableMap.of(),
            ImmutableMap.of(),
            ImmutableSet.of(),
            Optional.of(false));
      }

      @Override
      public NativeLinkTarget getNativeLinkTarget(final PythonPlatform pythonPlatform) {
        return new NativeLinkTarget() {

          @Override
          public BuildTarget getBuildTarget() {
            return params.getBuildTarget().withAppendedFlavors(pythonPlatform.getFlavor());
          }

          @Override
          public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
            return NativeLinkTargetMode.library();
          }

          @Override
          public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
              CxxPlatform cxxPlatform) {
            return RichStream.from(getPlatformDeps(ruleResolver, pythonPlatform, cxxPlatform, args))
                .filter(NativeLinkable.class)
                .toImmutableList();
          }

          @Override
          public NativeLinkableInput getNativeLinkTargetInput(CxxPlatform cxxPlatform)
              throws NoSuchBuildTargetException {
            return NativeLinkableInput.builder()
                .addAllArgs(
                    getExtensionArgs(
                        params.withBuildTarget(
                            params.getBuildTarget().withAppendedFlavors(
                                pythonPlatform.getFlavor(),
                                CxxDescriptionEnhancer.SHARED_FLAVOR)),
                        ruleResolver,
                        pathResolver,
                        ruleFinder,
                        cellRoots,
                        cxxPlatform,
                        args,
                        getPlatformDeps(ruleResolver, pythonPlatform, cxxPlatform, args),
                        pythonPlatform.getEnvironment().getPythonBuildConfig()))
                .addAllFrameworks(args.frameworks)
                .build();
          }

          @Override
          public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
            return Optional.empty();
          }

        };
      }

      @Override
      public Stream<BuildTarget> getRuntimeDeps() {
        return getDeclaredDeps().stream().map(BuildRule::getBuildTarget);
      }

    };
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // Get any parse time deps from the C/C++ platforms.
    extraDepsBuilder.addAll(CxxPlatforms.getParseTimeDeps(cxxPlatforms.getValues()));

    for (PythonPlatform pythonPlatform : pythonPlatforms.getValues()) {
      extraDepsBuilder.addAll(OptionalCompat.asSet(pythonPlatform.getCxxLibrary()));
    }
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> baseModule;
    public Optional<String> moduleName;
  }

}
