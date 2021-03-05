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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasTests;
import com.facebook.buck.core.description.attr.ImplicitDepsInferringDescription;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.filesystems.RelPath;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorConvertible;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.Flavored;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.rules.impl.SymlinkTree;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.ToolchainProvider;
import com.facebook.buck.core.util.Optionals;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.cxx.config.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.CxxPlatformsProvider;
import com.facebook.buck.cxx.toolchain.UnresolvedCxxPlatform;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkStrategy;
import com.facebook.buck.downwardapi.config.DownwardApiConfig;
import com.facebook.buck.features.python.PythonBuckConfig.PackageStyle;
import com.facebook.buck.features.python.toolchain.PexToolProvider;
import com.facebook.buck.features.python.toolchain.PythonPlatform;
import com.facebook.buck.features.python.toolchain.PythonPlatformsProvider;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.facebook.buck.versions.HasVersionUniverse;
import com.facebook.buck.versions.VersionRoot;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.immutables.value.Value;

public class PythonBinaryDescription
    implements DescriptionWithTargetGraph<PythonBinaryDescriptionArg>,
        ImplicitDepsInferringDescription<
            PythonBinaryDescription.AbstractPythonBinaryDescriptionArg>,
        VersionRoot<PythonBinaryDescriptionArg>,
        Flavored {

  private final ToolchainProvider toolchainProvider;
  private final PythonBuckConfig pythonBuckConfig;
  private final CxxBuckConfig cxxBuckConfig;
  private final DownwardApiConfig downwardApiConfig;

  static FlavorDomain<PythonBuckConfig.PackageStyle> PACKAGE_STYLE =
      FlavorDomain.from("Package Style", PythonBuckConfig.PackageStyle.class);

  static FlavorDomain<BinaryType> BINARY_TYPE = FlavorDomain.from("Binary Type", BinaryType.class);

  /** Ways of building this binary. */
  enum BinaryType implements FlavorConvertible {
    /** Compile the sources in this library into bytecode. */
    EXECUTABLE(InternalFlavor.of("binary")),
    SOURCE_DB(InternalFlavor.of("source-db")),
    ;

    private final Flavor flavor;

    BinaryType(Flavor flavor) {
      this.flavor = flavor;
    }

    @Override
    public Flavor getFlavor() {
      return flavor;
    }
  }

  public PythonBinaryDescription(
      ToolchainProvider toolchainProvider,
      PythonBuckConfig pythonBuckConfig,
      CxxBuckConfig cxxBuckConfig,
      DownwardApiConfig downwardApiConfig) {
    this.toolchainProvider = toolchainProvider;
    this.pythonBuckConfig = pythonBuckConfig;
    this.cxxBuckConfig = cxxBuckConfig;
    this.downwardApiConfig = downwardApiConfig;
  }

  @Override
  public Class<PythonBinaryDescriptionArg> getConstructorArgType() {
    return PythonBinaryDescriptionArg.class;
  }

  public static BuildTarget getEmptyInitTarget(BuildTarget baseTarget) {
    return baseTarget.withAppendedFlavors(InternalFlavor.of("__init__"));
  }

  @Override
  public Optional<ImmutableSet<FlavorDomain<?>>> flavorDomains(
      TargetConfiguration toolchainTargetConfiguration) {
    return Optional.of(ImmutableSet.of(PACKAGE_STYLE, BINARY_TYPE));
  }

  public static SourcePath createEmptyInitModule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      ActionGraphBuilder graphBuilder) {
    BuildTarget emptyInitTarget = getEmptyInitTarget(buildTarget);
    RelPath emptyInitPath =
        BuildTargetPaths.getGenPath(
            projectFilesystem.getBuckPaths(), buildTarget, "%s/__init__.py");
    WriteFile rule =
        graphBuilder.addToIndex(
            new WriteFile(
                emptyInitTarget,
                projectFilesystem,
                "",
                emptyInitPath.getPath(), /* executable */
                false));
    return rule.getSourcePathToOutput();
  }

  public static ImmutableMap<Path, SourcePath> addMissingInitModules(
      ImmutableMap<Path, SourcePath> modules, SourcePath emptyInit) {

    Map<Path, SourcePath> initModules = new LinkedHashMap<>();

    // Insert missing `__init__.py` modules.
    Set<Path> packages = new HashSet<>();
    for (Path module : modules.keySet()) {
      Path pkg = module;
      while ((pkg = pkg.getParent()) != null && !packages.contains(pkg)) {
        Path init = pkg.resolve("__init__.py");
        if (!modules.containsKey(init)) {
          initModules.put(init, emptyInit);
        }
        packages.add(pkg);
      }
    }

    return ImmutableMap.<Path, SourcePath>builder().putAll(modules).putAll(initModules).build();
  }

  private PythonInPlaceBinary createInPlaceBinaryRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      Optional<String> extension,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries,
      PackageStyle packageStyle) {

    SourcePath emptyInit = createEmptyInitModule(buildTarget, projectFilesystem, graphBuilder);
    BuildTarget linkTreeTarget = buildTarget.withAppendedFlavors(InternalFlavor.of("link-tree"));
    RelPath linkTreeRoot =
        BuildTargetPaths.getGenPath(projectFilesystem.getBuckPaths(), linkTreeTarget, "%s");
    SymlinkTree linkTree =
        graphBuilder.addToIndex(
            new SymlinkTree(
                "python_in_place_binary",
                linkTreeTarget,
                projectFilesystem,
                graphBuilder,
                linkTreeRoot.getPath(),
                components.withDefaultInitPy(emptyInit).asSymlinks()));

    return new PythonInPlaceBinary(
        buildTarget,
        projectFilesystem,
        graphBuilder,
        params.getDeclaredDeps(),
        cxxPlatform,
        pythonPlatform,
        mainModule,
        components,
        extension.orElse(pythonBuckConfig.getPexExtension()),
        preloadLibraries,
        pythonBuckConfig.legacyOutputPath(),
        linkTree,
        pythonPlatform.getEnvironment(),
        packageStyle);
  }

  PythonBinary createPackageRule(
      CellPathResolver cellRoots,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      ActionGraphBuilder graphBuilder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      Optional<String> extension,
      PythonPackageComponents components,
      ImmutableList<StringWithMacros> buildArgs,
      PythonBuckConfig.PackageStyle packageStyle,
      ImmutableSet<String> preloadLibraries) {

    switch (packageStyle) {
      case INPLACE_LITE:
      case INPLACE:
        return createInPlaceBinaryRule(
            buildTarget,
            projectFilesystem,
            params,
            graphBuilder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            extension,
            components,
            preloadLibraries,
            packageStyle);

      case STANDALONE:
        StringWithMacrosConverter macrosConverter =
            StringWithMacrosConverter.of(
                buildTarget,
                cellRoots.getCellNameResolver(),
                graphBuilder,
                ImmutableList.of(
                    LocationMacroExpander.INSTANCE,
                    new ExecutableMacroExpander<>(ExecutableMacro.class),
                    new ExecutableMacroExpander<>(ExecutableTargetMacro.class)),
                Optional.empty());
        return new PythonPackagedBinary(
            buildTarget,
            projectFilesystem,
            graphBuilder,
            params.getDeclaredDeps(),
            pythonPlatform,
            toolchainProvider
                .getByName(
                    PexToolProvider.DEFAULT_NAME,
                    buildTarget.getTargetConfiguration(),
                    PexToolProvider.class)
                .getPexTool(graphBuilder, buildTarget.getTargetConfiguration()),
            buildArgs.stream()
                .map(macrosConverter::convert)
                .collect(ImmutableList.toImmutableList()),
            pythonBuckConfig
                .getPexExecutor(graphBuilder, buildTarget.getTargetConfiguration())
                .orElse(pythonPlatform.getEnvironment()),
            extension.orElse(pythonBuckConfig.getPexExtension()),
            pythonPlatform.getEnvironment(),
            mainModule,
            components,
            preloadLibraries,
            pythonBuckConfig.shouldCacheBinaries(),
            pythonBuckConfig.legacyOutputPath(),
            downwardApiConfig.isEnabledForPython());

      default:
        throw new IllegalStateException();
    }
  }

  private UnresolvedCxxPlatform getCxxPlatform(
      BuildTarget target, AbstractPythonBinaryDescriptionArg args) {
    CxxPlatformsProvider cxxPlatformsProvider =
        toolchainProvider.getByName(
            CxxPlatformsProvider.DEFAULT_NAME,
            target.getTargetConfiguration(),
            CxxPlatformsProvider.class);
    FlavorDomain<UnresolvedCxxPlatform> cxxPlatforms =
        cxxPlatformsProvider.getUnresolvedCxxPlatforms();
    return cxxPlatforms
        .getValue(target)
        .orElse(
            args.getCxxPlatform()
                .map(cxxPlatforms::getValue)
                .orElse(cxxPlatformsProvider.getDefaultUnresolvedCxxPlatform()));
  }

  private PythonBuckConfig.PackageStyle getPackageStyle(
      BuildTarget target, AbstractPythonBinaryDescriptionArg args) {
    return PACKAGE_STYLE
        .getValue(target)
        .orElse(args.getPackageStyle().orElse(pythonBuckConfig.getPackageStyle()));
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonBinaryDescriptionArg args) {
    if (args.getMain().isPresent() == args.getMainModule().isPresent()) {
      throw new HumanReadableException(
          "%s: must set exactly one of `main_module` and `main`", buildTarget);
    }
    Path baseModule = PythonUtil.getBasePath(buildTarget, args.getBaseModule());

    ActionGraphBuilder graphBuilder = context.getActionGraphBuilder();

    // If `main` is set, add it to the map of modules for this binary and also set it as the
    // `mainModule`, otherwise, use the explicitly set main module.
    String mainModule;
    Optional<PythonMappedComponents> modules;
    if (args.getMain().isPresent()) {
      String mainName =
          graphBuilder.getSourcePathResolver().getSourcePathName(buildTarget, args.getMain().get());
      Path main = baseModule.resolve(mainName);
      mainModule = PythonUtil.toModuleName(buildTarget, main.toString());
      SourcePath mainSrc = args.getMain().get();
      PythonUtil.checkSrcExt(
          pythonBuckConfig.getSrcExtCheckStyle(), graphBuilder, "main", main, mainSrc);
      modules =
          Optional.of(
              PythonMappedComponents.of(
                  ImmutableSortedMap.of(baseModule.resolve(mainName), mainSrc)));
    } else {
      mainModule = args.getMainModule().get();
      modules = Optional.empty();
    }

    FlavorDomain<PythonPlatform> pythonPlatforms =
        toolchainProvider
            .getByName(
                PythonPlatformsProvider.DEFAULT_NAME,
                buildTarget.getTargetConfiguration(),
                PythonPlatformsProvider.class)
            .getPythonPlatforms();

    // Extract the platforms from the flavor, falling back to the default platforms if none are
    // found.
    PythonPlatform pythonPlatform =
        pythonPlatforms
            .getValue(buildTarget)
            .orElse(
                pythonPlatforms.getValue(
                    args.getPlatform()
                        .<Flavor>map(InternalFlavor::of)
                        .orElse(pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform =
        getCxxPlatform(buildTarget, args)
            .resolve(graphBuilder, buildTarget.getTargetConfiguration());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();

    switch (BINARY_TYPE.getValue(buildTarget).orElse(BinaryType.EXECUTABLE)) {
      case SOURCE_DB:
        {
          return PythonSourceDatabase.from(
              buildTarget,
              context.getProjectFilesystem(),
              context.getActionGraphBuilder(),
              pythonPlatform,
              cxxPlatform,
              modules.orElseGet(() -> PythonMappedComponents.of(ImmutableSortedMap.of())),
              PythonUtil.getParamForPlatform(
                      pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps())
                  .stream()
                  .map(graphBuilder::getRule)
                  .collect(ImmutableList.toImmutableList()));
        }
      case EXECUTABLE:
        {

          // Build up the list of all components going into the python binary.
          PythonPackagable root =
              ImmutablePythonBinaryPackagable.ofImpl(
                  buildTarget,
                  projectFilesystem,
                  PythonUtil.getParamForPlatform(
                          pythonPlatform, cxxPlatform, args.getDeps(), args.getPlatformDeps())
                      .stream()
                      .map(graphBuilder::getRule)
                      .collect(ImmutableList.toImmutableList()),
                  modules,
                  Optional.empty(),
                  args.getZipSafe(),
                  downwardApiConfig.isEnabledForPython());

          CellPathResolver cellRoots = context.getCellPathResolver();
          StringWithMacrosConverter macrosConverter =
              StringWithMacrosConverter.of(
                  buildTarget,
                  cellRoots.getCellNameResolver(),
                  graphBuilder,
                  PythonUtil.macroExpanders(context.getTargetGraph()));
          PythonPackageComponents allPackageComponents =
              PythonUtil.getAllComponents(
                  cellRoots,
                  buildTarget,
                  projectFilesystem,
                  params,
                  graphBuilder,
                  root,
                  pythonPlatform,
                  cxxBuckConfig,
                  downwardApiConfig,
                  cxxPlatform,
                  PythonUtil.getParamForPlatform(
                          pythonPlatform,
                          cxxPlatform,
                          args.getLinkerFlags(),
                          args.getPlatformLinkerFlags())
                      .stream()
                      .map(macrosConverter::convert)
                      .collect(ImmutableList.toImmutableList()),
                  args.getNativeLinkStrategy().orElse(pythonBuckConfig.getNativeLinkStrategy()),
                  args.getPreloadDeps(),
                  args.getCompile().orElse(false),
                  args.getPreferStrippedNativeObjects(),
                  args.getDeduplicateMergedLinkRoots());
          return createPackageRule(
              cellRoots,
              buildTarget,
              projectFilesystem,
              params,
              graphBuilder,
              pythonPlatform,
              cxxPlatform,
              mainModule,
              args.getExtension(),
              allPackageComponents,
              args.getBuildArgs(),
              getPackageStyle(buildTarget, args),
              PythonUtil.getPreloadNames(graphBuilder, cxxPlatform, args.getPreloadDeps()));
        }
      default:
        throw new IllegalStateException();
    }
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellNameResolver cellRoots,
      AbstractPythonBinaryDescriptionArg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    extraDepsBuilder.addAll(
        getCxxPlatform(buildTarget, constructorArg)
            .getLinkerParseTimeDeps(buildTarget.getTargetConfiguration()));

    if (getPackageStyle(buildTarget, constructorArg) == PythonBuckConfig.PackageStyle.STANDALONE) {
      Optionals.addIfPresent(
          pythonBuckConfig.getPexTarget(buildTarget.getTargetConfiguration()), extraDepsBuilder);
      Optionals.addIfPresent(
          pythonBuckConfig.getPexExecutorTarget(buildTarget.getTargetConfiguration()),
          extraDepsBuilder);
    }

    // Make sure we parse the dummy omnibus target if we're using omnibus linking.
    if (constructorArg.getNativeLinkStrategy().orElse(pythonBuckConfig.getNativeLinkStrategy())
        == NativeLinkStrategy.MERGED) {
      cxxBuckConfig
          .getDummyOmnibusTarget()
          .ifPresent(
              target ->
                  targetGraphOnlyDepsBuilder.add(
                      target.configure(buildTarget.getTargetConfiguration())));
    }
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  interface PythonBinaryCommonArg {
    @Value.Default
    default boolean getPreferStrippedNativeObjects() {
      return false;
    }

    Optional<NativeLinkStrategy> getNativeLinkStrategy();

    Optional<Boolean> getDeduplicateMergedLinkRoots();
  }

  @RuleArg
  interface AbstractPythonBinaryDescriptionArg
      extends PythonBinaryCommonArg, BuildRuleArg, HasDeclaredDeps, HasTests, HasVersionUniverse {
    Optional<SourcePath> getMain();

    Optional<String> getMainModule();

    @Value.Default
    default PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> getPlatformDeps() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getBaseModule();

    Optional<Boolean> getZipSafe();

    ImmutableList<StringWithMacros> getBuildArgs();

    Optional<String> getPlatform();

    Optional<Flavor> getCxxPlatform();

    Optional<PythonBuckConfig.PackageStyle> getPackageStyle();

    ImmutableSet<BuildTarget> getPreloadDeps();

    ImmutableList<StringWithMacros> getLinkerFlags();

    @Value.Default
    default PatternMatchedCollection<ImmutableList<StringWithMacros>> getPlatformLinkerFlags() {
      return PatternMatchedCollection.of();
    }

    Optional<String> getExtension();

    Optional<Boolean> getCompile();
  }
}
