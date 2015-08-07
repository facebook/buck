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

import com.facebook.buck.cxx.CxxPlatform;
import com.facebook.buck.cxx.WindowsLinker;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.log.Logger;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.model.FlavorDomainException;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class PythonBinaryDescription implements Description<PythonBinaryDescription.Arg> {

  private static final Logger LOG = Logger.get(PythonBinaryDescription.class);

  public static final BuildRuleType TYPE = BuildRuleType.of("python_binary");

  private final PythonBuckConfig pythonBuckConfig;
  private final PythonEnvironment pythonEnvironment;
  private final CxxPlatform defaultCxxPlatform;
  private final FlavorDomain<CxxPlatform> cxxPlatforms;

  public PythonBinaryDescription(
      PythonBuckConfig pythonBuckConfig,
      PythonEnvironment pythonEnv,
      CxxPlatform defaultCxxPlatform,
      FlavorDomain<CxxPlatform> cxxPlatforms) {
    this.pythonBuckConfig = pythonBuckConfig;
    this.pythonEnvironment = pythonEnv;
    this.defaultCxxPlatform = defaultCxxPlatform;
    this.cxxPlatforms = cxxPlatforms;
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  private PythonInPlaceBinary createInPlaceBinaryRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      String mainModule,
      PythonPackageComponents components) {

    // We don't currently support targeting Windows.
    if (cxxPlatform.getLd() instanceof WindowsLinker) {
      throw new HumanReadableException(
          "%s: cannot build in-place python binaries for Windows (%s)",
          params.getBuildTarget(),
          cxxPlatform.getFlavor());
    }

    BuildTarget linkTreeTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("link-tree"))
            .build();
    Path linkTreeRoot = BuildTargets.getGenPath(linkTreeTarget, "%s");
    SymlinkTree linkTree;
    try {
      linkTree =
        resolver.addToIndex(
            new SymlinkTree(
                params.copyWithChanges(
                    linkTreeTarget,
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
                pathResolver,
                linkTreeRoot,
                ImmutableMap.<Path, SourcePath>builder()
                    .putAll(components.getModules())
                    .putAll(components.getResources())
                    .putAll(components.getNativeLibraries())
                    .build()));
    } catch (SymlinkTree.InvalidSymlinkTreeException e) {
      throw e.getHumanReadableExceptionForBuildTarget(params.getBuildTarget());
    }

    Map<String, String> env = Maps.newLinkedHashMap();

    // If we have native libraries, set the dynamic loader search path so it can find them.
    if (!components.getNativeLibraries().isEmpty()) {
      env.put(cxxPlatform.getLd().searchPathEnvVar(), linkTreeRoot.toString());
    }

    // Set the python path, so python can find all the modules.
    env.put("PYTHONPATH", linkTreeRoot.toString());

    // Setup the python command to run the program.
    List<String> args = Lists.newArrayList();
    args.add(pythonEnvironment.getPythonPath().toString());
    args.add("-m");
    args.add(mainModule);

    // Format the command into a string.
    List<String> escapedCmdArgs = Lists.newArrayList();
    for (Map.Entry<String, String> entry : env.entrySet()) {
      escapedCmdArgs.add(
          String.format(
              "%s=%s",
              Escaper.escapeAsShellString(entry.getKey()),
              Escaper.escapeAsShellString(entry.getValue())));
    }
    for (String arg : args) {
      escapedCmdArgs.add(Escaper.escapeAsShellString(arg));
    }
    escapedCmdArgs.add("\"$@\"");
    String cmd = Joiner.on(' ').join(escapedCmdArgs);

    BuildTarget scriptTarget =
        BuildTarget.builder(params.getBuildTarget())
            .addFlavors(ImmutableFlavor.of("script"))
            .build();
     Path scriptPath =
         BuildTargets.getGenPath(
             params.getBuildTarget(),
             "%s" + pythonBuckConfig.getPexExtension());
     WriteFile script =
        resolver.addToIndex(
            new WriteFile(
                params.copyWithChanges(
                    scriptTarget,
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of()),
                    Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
                pathResolver,
                "#!/bin/sh\n" + cmd,
                scriptPath,
                /* executable */ true));

    return new PythonInPlaceBinary(
        params,
        pathResolver,
        script,
        linkTree,
        mainModule,
        components);
  }

  protected PythonBinary createPackageRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      String mainModule,
      PythonPackageComponents components,
      ImmutableList<String> buildArgs) {

    switch (pythonBuckConfig.getPackageStyle()) {
      case INPLACE:
        return createInPlaceBinaryRule(
            params,
            resolver,
            pathResolver,
            cxxPlatform,
            mainModule,
            components);
      case STANDALONE:
        return new PythonPackagedBinary(
            params.copyWithDeps(
                Suppliers.ofInstance(
                    PythonUtil.getDepsFromComponents(pathResolver, components)),
                Suppliers.ofInstance(ImmutableSortedSet.<BuildRule>of())),
            pathResolver,
            pythonBuckConfig.getPathToPex(),
            buildArgs,
            pythonBuckConfig.getPathToPexExecuter(),
            pythonBuckConfig.getPexExtension(),
            pythonEnvironment,
            mainModule,
            components);
      default:
        throw new IllegalStateException();
    }

  }

  @Override
  public <A extends Arg> PythonBinary createBuildRule(
      TargetGraph targetGraph,
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args) {

    // Extract the platform from the flavor, falling back to the default platform if none are
    // found.
    CxxPlatform cxxPlatform;
    try {
      cxxPlatform = cxxPlatforms
          .getValue(ImmutableSet.copyOf(params.getBuildTarget().getFlavors()))
          .or(defaultCxxPlatform);
    } catch (FlavorDomainException e) {
      throw new HumanReadableException("%s: %s", params.getBuildTarget(), e.getMessage());
    }

    if (!(args.main.isPresent() ^ args.mainModule.isPresent())) {
      throw new HumanReadableException(
          "%s: must set exactly one of `main_module` and `main`",
          params.getBuildTarget());
    }

    SourcePathResolver pathResolver = new SourcePathResolver(resolver);
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);

    String mainModule;
    ImmutableMap.Builder<Path, SourcePath> modules = ImmutableMap.builder();

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
        /* resources */ ImmutableMap.<Path, SourcePath>of(),
        /* nativeLibraries */ ImmutableMap.<Path, SourcePath>of(),
        /* prebuiltLibraries */ ImmutableSet.<SourcePath>of(),
        /* zipSafe */ args.zipSafe);
    PythonPackageComponents allPackageComponents = PythonUtil.getAllComponents(
        targetGraph,
        params,
        binaryPackageComponents,
        cxxPlatform);

    return createPackageRule(
        params,
        resolver,
        pathResolver,
        cxxPlatform,
        mainModule,
        allPackageComponents,
        args.buildArgs.or(ImmutableList.<String>of()));
  }

  @SuppressFieldNotInitialized
  public static class Arg {
    public Optional<SourcePath> main;
    public Optional<String> mainModule;
    public Optional<ImmutableSortedSet<BuildTarget>> deps;
    public Optional<String> baseModule;
    public Optional<Boolean> zipSafe;
    public Optional<ImmutableList<String>> buildArgs;
  }

}
