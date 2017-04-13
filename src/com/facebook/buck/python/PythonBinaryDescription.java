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
import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.WindowsLinker;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.HasTests;
import com.facebook.buck.model.InternalFlavor;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.AbstractDescriptionArg;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.coercer.Hint;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.MacroArg;
import com.facebook.buck.rules.coercer.PatternMatchedCollection;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.OptionalCompat;
import com.facebook.buck.versions.VersionRoot;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PythonBinaryDescription implements
    Description<PythonBinaryDescription.Arg>,
    ImplicitDepsInferringDescription<PythonBinaryDescription.Arg>,
    VersionRoot<PythonBinaryDescription.Arg> {

  private static final Logger LOG = Logger.get(PythonBinaryDescription.class);

  private final PythonBuckConfig pythonBuckConfig;
  private final FlavorDomain<PythonPlatform> pythonPlatforms;
  private final CxxBuckConfig cxxBuckConfig;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PythonBinaryDescription(
      PythonBuckConfig pythonBuckConfig,
      FlavorDomain<PythonPlatform> pythonPlatforms,
      CxxBuckConfig cxxBuckConfig,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.pythonBuckConfig = pythonBuckConfig;
    this.pythonPlatforms = pythonPlatforms;
    this.cxxBuckConfig = cxxBuckConfig;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  public static BuildTarget getEmptyInitTarget(BuildTarget baseTarget) {
    return baseTarget.withAppendedFlavors(InternalFlavor.of("__init__"));
  }

  public static SourcePath createEmptyInitModule(
      BuildRuleParams params,
      BuildRuleResolver resolver) {
    BuildTarget emptyInitTarget = getEmptyInitTarget(params.getBuildTarget());
    Path emptyInitPath =
        BuildTargets.getGenPath(
            params.getProjectFilesystem(),
            params.getBuildTarget(),
            "%s/__init__.py");
    WriteFile rule = resolver.addToIndex(
        new WriteFile(
            params
                .withBuildTarget(emptyInitTarget)
                .copyReplacingDeclaredAndExtraDeps(
                    Suppliers.ofInstance(ImmutableSortedSet.of()),
                    Suppliers.ofInstance(ImmutableSortedSet.of())),
            "",
            emptyInitPath,
            /* executable */ false));
    return rule.getSourcePathToOutput();
  }

  public static ImmutableMap<Path, SourcePath> addMissingInitModules(
      ImmutableMap<Path, SourcePath> modules,
      SourcePath emptyInit) {

    Map<Path, SourcePath> initModules = Maps.newLinkedHashMap();

    // Insert missing `__init__.py` modules.
    Set<Path> packages = Sets.newHashSet();
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

    return ImmutableMap.<Path, SourcePath>builder()
        .putAll(modules)
        .putAll(initModules)
        .build();
  }

  private PythonInPlaceBinary createInPlaceBinaryRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      Optional<String> extension,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries) {

    // We don't currently support targeting Windows.
    if (cxxPlatform.getLd().resolve(resolver) instanceof WindowsLinker) {
      throw new HumanReadableException(
          "%s: cannot build in-place python binaries for Windows (%s)",
          params.getBuildTarget(),
          cxxPlatform.getFlavor());
    }

    // Add in any missing init modules into the python components.
    SourcePath emptyInit = createEmptyInitModule(params, resolver);
    components = components.withModules(addMissingInitModules(components.getModules(), emptyInit));

    BuildTarget linkTreeTarget =
        params.getBuildTarget().withAppendedFlavors(InternalFlavor.of("link-tree"));
    Path linkTreeRoot =
        BuildTargets.getGenPath(params.getProjectFilesystem(), linkTreeTarget, "%s");
    SymlinkTree linkTree =
        resolver.addToIndex(
            new SymlinkTree(
                linkTreeTarget,
                params.getProjectFilesystem(),
                linkTreeRoot,
                ImmutableMap.<Path, SourcePath>builder()
                    .putAll(components.getModules())
                    .putAll(components.getResources())
                    .putAll(components.getNativeLibraries())
                    .build(),
                ruleFinder));

    return PythonInPlaceBinary.from(
        params,
        resolver,
        cxxPlatform,
        pythonPlatform,
        mainModule,
        components,
        extension.orElse(pythonBuckConfig.getPexExtension()),
        preloadLibraries,
        pythonBuckConfig.legacyOutputPath(),
        ruleFinder,
        linkTree,
        pythonPlatform.getEnvironment());
  }

  PythonBinary createPackageRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathRuleFinder ruleFinder,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      String mainModule,
      Optional<String> extension,
      PythonPackageComponents components,
      ImmutableList<String> buildArgs,
      PythonBuckConfig.PackageStyle packageStyle,
      ImmutableSet<String> preloadLibraries) {

    switch (packageStyle) {

      case INPLACE:
        return createInPlaceBinaryRule(
            params,
            resolver,
            ruleFinder,
            pythonPlatform,
            cxxPlatform,
            mainModule,
            extension,
            components,
            preloadLibraries);

      case STANDALONE:
        return PythonPackagedBinary.from(
            params,
            ruleFinder,
            pythonPlatform,
            pythonBuckConfig.getPexTool(resolver),
            buildArgs,
            pythonBuckConfig.getPexExecutor(resolver).orElse(pythonPlatform.getEnvironment()),
            extension.orElse(pythonBuckConfig.getPexExtension()),
            pythonPlatform.getEnvironment(),
            mainModule,
            components,
            preloadLibraries,
            pythonBuckConfig.shouldCacheBinaries(),
            pythonBuckConfig.legacyOutputPath());

      default:
        throw new IllegalStateException();

    }

  }

  private CxxPlatform getCxxPlatform(BuildTarget target, Arg args) {
    return cxxPlatforms.getValue(target)
        .orElse(args.cxxPlatform.map(cxxPlatforms::getValue)
            .orElse(defaultCxxPlatform));
  }

  @Override
  public <A extends Arg> PythonBinary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      CellPathResolver cellRoots,
      A args) throws NoSuchBuildTargetException {
    if (!(args.main.isPresent() ^ args.mainModule.isPresent())) {
      throw new HumanReadableException(
          "%s: must set exactly one of `main_module` and `main`",
          params.getBuildTarget());
    }
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);

    String mainModule;
    ImmutableMap.Builder<Path, SourcePath> modules = ImmutableMap.builder();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(resolver);
    SourcePathResolver pathResolver = new SourcePathResolver(ruleFinder);

    // If `main` is set, add it to the map of modules for this binary and also set it as the
    // `mainModule`, otherwise, use the explicitly set main module.
    if (args.main.isPresent()) {
      LOG.warn(
          "%s: parameter `main` is deprecated, please use `main_module` instead.",
          params.getBuildTarget());
      String mainName = pathResolver.getSourcePathName(params.getBuildTarget(), args.main.get());
      Path main = baseModule.resolve(mainName);
      modules.put(baseModule.resolve(mainName), args.main.get());
      mainModule = PythonUtil.toModuleName(params.getBuildTarget(), main.toString());
    } else {
      mainModule = args.mainModule.get();
    }
    // Build up the list of all components going into the python binary.
    PythonPackageComponents binaryPackageComponents = PythonPackageComponents.of(
        modules.build(),
        /* resources */ ImmutableMap.of(),
        /* nativeLibraries */ ImmutableMap.of(),
        /* prebuiltLibraries */ ImmutableSet.of(),
        /* zipSafe */ args.zipSafe);
    // Extract the platforms from the flavor, falling back to the default platforms if none are
    // found.
    PythonPlatform pythonPlatform =
        pythonPlatforms.getValue(params.getBuildTarget()).orElse(
            pythonPlatforms.getValue(
                args.platform.<Flavor>map(InternalFlavor::of).orElse(
                    pythonPlatforms.getFlavors().iterator().next())));
    CxxPlatform cxxPlatform = getCxxPlatform(params.getBuildTarget(), args);
    PythonPackageComponents allPackageComponents =
        PythonUtil.getAllComponents(
            params,
            resolver,
            ruleFinder,
            PythonUtil.getDeps(pythonPlatform, cxxPlatform, args.deps, args.platformDeps).stream()
                .map(resolver::getRule)
                .collect(MoreCollectors.toImmutableList()),
            binaryPackageComponents,
            pythonPlatform,
            cxxBuckConfig,
            cxxPlatform,
            args.linkerFlags.stream()
                .map(MacroArg.toMacroArgFunction(
                    PythonUtil.MACRO_HANDLER,
                    params.getBuildTarget(),
                    cellRoots,
                    resolver)::apply)
                .collect(MoreCollectors.toImmutableList()),
            pythonBuckConfig.getNativeLinkStrategy(),
            args.preloadDeps);
    return createPackageRule(
        params,
        resolver,
        ruleFinder,
        pythonPlatform,
        cxxPlatform,
        mainModule,
        args.extension,
        allPackageComponents,
        args.buildArgs,
        args.packageStyle.orElse(pythonBuckConfig.getPackageStyle()),
        PythonUtil.getPreloadNames(
            resolver,
            cxxPlatform,
            args.preloadDeps));
  }

  @Override
  public void findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      CellPathResolver cellRoots,
      Arg constructorArg,
      ImmutableCollection.Builder<BuildTarget> extraDepsBuilder,
      ImmutableCollection.Builder<BuildTarget> targetGraphOnlyDepsBuilder) {
    // We need to use the C/C++ linker for native libs handling, so add in the C/C++ linker to
    // parse time deps.
    extraDepsBuilder.addAll(getCxxPlatform(buildTarget, constructorArg).getLd().getParseTimeDeps());

    if (constructorArg.packageStyle.orElse(pythonBuckConfig.getPackageStyle()) ==
        PythonBuckConfig.PackageStyle.STANDALONE) {
      extraDepsBuilder.addAll(OptionalCompat.asSet(pythonBuckConfig.getPexTarget()));
      extraDepsBuilder.addAll(OptionalCompat.asSet(pythonBuckConfig.getPexExecutorTarget()));
    }
  }

  @Override
  public boolean isVersionRoot(ImmutableSet<Flavor> flavors) {
    return true;
  }

  @SuppressFieldNotInitialized
  public static class Arg extends AbstractDescriptionArg implements HasTests {
    public Optional<SourcePath> main;
    public Optional<String> mainModule;
    public ImmutableSortedSet<BuildTarget> deps = ImmutableSortedSet.of();
    public PatternMatchedCollection<ImmutableSortedSet<BuildTarget>> platformDeps =
        PatternMatchedCollection.of();
    public Optional<String> baseModule;
    public Optional<Boolean> zipSafe;
    public ImmutableList<String> buildArgs = ImmutableList.of();
    public Optional<String> platform;
    public Optional<Flavor> cxxPlatform;
    public Optional<PythonBuckConfig.PackageStyle> packageStyle;
    public ImmutableSet<BuildTarget> preloadDeps = ImmutableSet.of();
    public ImmutableList<String> linkerFlags = ImmutableList.of();
    public Optional<String> extension;
    @Hint(isDep = false) public ImmutableSortedSet<BuildTarget> tests = ImmutableSortedSet.of();

    @Override
    public ImmutableSortedSet<BuildTarget> getTests() {
      return tests;
    }

    public Optional<String> versionUniverse;

  }

}
