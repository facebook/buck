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
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.HeaderSymlinkTree;
import com.facebook.buck.cxx.HeaderVisibility;
import com.facebook.buck.cxx.Linker;
import com.facebook.buck.cxx.Linkers;
import com.facebook.buck.cxx.NativeLinkable;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.cxx.SharedNativeLinkTarget;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildRuleType;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.ImplicitDepsInferringDescription;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Sets;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class CxxPythonExtensionDescription implements
    Description<CxxPythonExtensionDescription.Arg>,
    ImplicitDepsInferringDescription<CxxPythonExtensionDescription.Arg> {

  private enum Type {
    EXTENSION,
  }

  private static final FlavorDomain<Type> LIBRARY_TYPE =
      new FlavorDomain<>(
          "C/C++ Library Type",
          ImmutableMap.of(
              CxxDescriptionEnhancer.SHARED_FLAVOR, Type.EXTENSION));

  public static final BuildRuleType TYPE = BuildRuleType.of("cxx_python_extension");

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
  public Arg createUnpopulatedConstructorArg() {
    return new Arg();
  }

  @VisibleForTesting
  protected static BuildTarget getExtensionTarget(
      BuildTarget target,
      Flavor pythonPlatform,
      Flavor platform) {
    return CxxDescriptionEnhancer.createSharedLibraryBuildTarget(
        BuildTarget.builder(target)
            .addFlavors(pythonPlatform)
            .build(),
        platform);
  }

  @VisibleForTesting
  protected static String getExtensionName(BuildTarget target) {
    // .so is used on OS X too (as opposed to dylib).
    return String.format("%s.so", target.getShortName());
  }

  @VisibleForTesting
  protected Path getExtensionPath(BuildTarget target, Flavor pythonPlatform, Flavor platform) {
    return BuildTargets.getGenPath(getExtensionTarget(target, pythonPlatform, platform), "%s")
        .resolve(getExtensionName(target));
  }

  private ImmutableList<com.facebook.buck.rules.args.Arg> getExtensionArgs(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      CxxPlatform cxxPlatform,
      Arg args) throws NoSuchBuildTargetException {

    // Extract all C/C++ sources from the constructor arg.
    ImmutableMap<String, CxxSource> srcs =
        CxxDescriptionEnhancer.parseCxxSources(
            params.getBuildTarget(),
            pathResolver,
            cxxPlatform,
            args);
    ImmutableMap<Path, SourcePath> headers =
        CxxDescriptionEnhancer.parseHeaders(
            params.getBuildTarget(),
            pathResolver,
            Optional.of(cxxPlatform),
            args);

    // Setup the header symlink tree and combine all the preprocessor input from this rule
    // and all dependencies.
    HeaderSymlinkTree headerSymlinkTree =
        CxxDescriptionEnhancer.requireHeaderSymlinkTree(
            params,
            ruleResolver,
            new SourcePathResolver(ruleResolver),
            cxxPlatform,
            headers,
            HeaderVisibility.PRIVATE);
    ImmutableList<CxxPreprocessorInput> cxxPreprocessorInput =
        CxxDescriptionEnhancer.collectCxxPreprocessorInput(
            params,
            cxxPlatform,
            CxxFlags.getLanguageFlags(
                args.preprocessorFlags,
                args.platformPreprocessorFlags,
                args.langPreprocessorFlags,
                cxxPlatform),
            ImmutableList.of(headerSymlinkTree),
            ImmutableSet.<FrameworkPath>of(),
            CxxPreprocessables.getTransitiveCxxPreprocessorInput(
                cxxPlatform,
                params.getDeps()));

    // Generate rule to build the object files.
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> picObjects =
        CxxSourceRuleFactory.requirePreprocessAndCompileRules(
            params,
            ruleResolver,
            pathResolver,
            cxxPlatform,
            cxxPreprocessorInput,
            CxxFlags.getFlags(
                args.compilerFlags,
                args.platformCompilerFlags,
                cxxPlatform),
            args.prefixHeader,
            cxxBuckConfig.getPreprocessMode(),
            srcs,
            CxxSourceRuleFactory.PicType.PIC);

    ImmutableList.Builder<com.facebook.buck.rules.args.Arg> argsBuilder = ImmutableList.builder();
    argsBuilder.addAll(
        StringArg.from(
            CxxFlags.getFlags(
                args.linkerFlags,
                args.platformLinkerFlags,
                cxxPlatform)));

    // Embed a origin-relative library path into the binary so it can find the shared libraries.
    argsBuilder.addAll(
        StringArg.from(
            Linkers.iXlinker("-rpath", String.format("%s/", cxxPlatform.getLd().libOrigin()))));

    // Add object files into the args.
    argsBuilder.addAll(SourcePathArg.from(pathResolver, picObjects.values()));

    return argsBuilder.build();
  }

  private <A extends Arg> BuildRule createExtensionBuildRule(
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      PythonPlatform pythonPlatform,
      CxxPlatform cxxPlatform,
      A args) throws NoSuchBuildTargetException {
    SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    String extensionName = getExtensionName(params.getBuildTarget());
    Path extensionPath =
        getExtensionPath(
            params.getBuildTarget(),
            pythonPlatform.getFlavor(),
            cxxPlatform.getFlavor());
    return CxxLinkableEnhancer.createCxxLinkableBuildRule(
        cxxPlatform,
        params,
        pathResolver,
        getExtensionTarget(
            params.getBuildTarget(),
            pythonPlatform.getFlavor(),
            cxxPlatform.getFlavor()),
        Linker.LinkType.SHARED,
        Optional.of(extensionName),
        extensionPath,
        getExtensionArgs(params, ruleResolver, pathResolver, cxxPlatform, args),
        Linker.LinkableDepType.SHARED,
        params.getDeps(),
        args.cxxRuntimeType,
        Optional.<SourcePath>absent(),
        ImmutableSet.<BuildTarget>of(),
        args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()));
  }

  @Override
  public <A extends Arg> BuildRule createBuildRule(
      TargetGraph targetGraph,
      final BuildRuleParams params,
      final BuildRuleResolver ruleResolver,
      final A args) throws NoSuchBuildTargetException {

    // See if we're building a particular "type" of this library, and if so, extract
    // it as an enum.
    final Optional<Map.Entry<Flavor, Type>> type = LIBRARY_TYPE.getFlavorAndValue(
        params.getBuildTarget());
    Optional<Map.Entry<Flavor, CxxPlatform>> platform = cxxPlatforms.getFlavorAndValue(
        params.getBuildTarget());
    final Optional<Map.Entry<Flavor, PythonPlatform>> pythonPlatform =
        pythonPlatforms.getFlavorAndValue(params.getBuildTarget());

    // If we *are* building a specific type of this lib, call into the type specific
    // rule builder methods.  Currently, we only support building a shared lib from the
    // pre-existing static lib, which we do here.
    if (type.isPresent() && platform.isPresent() && pythonPlatform.isPresent()) {
      Preconditions.checkState(type.get().getValue() == Type.EXTENSION);
      return createExtensionBuildRule(
          // Reconstruct the params with irrelevant python platform C/C++ libs filtered out.
          params.copyWithExtraDeps(
              new Supplier<ImmutableSortedSet<BuildRule>>() {
                @Override
                public ImmutableSortedSet<BuildRule> get() {
                  BuildRule relevantCxxLibrary =
                      ruleResolver.getRule(pythonPlatform.get().getValue().getCxxLibrary().get());
                  Set<BuildRule> extraDeps = Sets.newHashSet(params.getExtraDeps().get());
                  for (PythonPlatform python : pythonPlatforms.getValues()) {
                    Optional<BuildTarget> cxxLibraryTarget = python.getCxxLibrary();
                    if (cxxLibraryTarget.isPresent()) {
                      Optional<BuildRule> cxxLibrary =
                          ruleResolver.getRuleOptional(cxxLibraryTarget.get());
                      if (cxxLibrary.isPresent() && !cxxLibrary.get().equals(relevantCxxLibrary)) {
                        extraDeps.remove(cxxLibrary.get());
                      }
                    }
                  }
                  return ImmutableSortedSet.copyOf(extraDeps);
                }
              }),
          ruleResolver,
          pythonPlatform.get().getValue(),
          platform.get().getValue(),
          args);
    }

    // Otherwise, we return the generic placeholder of this library, that dependents can use
    // get the real build rules via querying the action graph.
    final SourcePathResolver pathResolver = new SourcePathResolver(ruleResolver);
    Path baseModule = PythonUtil.getBasePath(params.getBuildTarget(), args.baseModule);
    final Path module = baseModule.resolve(getExtensionName(params.getBuildTarget()));
    return new CxxPythonExtension(params, pathResolver) {

      @Override
      protected BuildRule getExtension(
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        return ruleResolver.requireRule(
            getBuildTarget().withFlavors(
                pythonPlatform.getFlavor(),
                cxxPlatform.getFlavor(),
                CxxDescriptionEnhancer.SHARED_FLAVOR));
      }

      @Override
      public PythonPackageComponents getPythonPackageComponents(
          PythonPlatform pythonPlatform,
          CxxPlatform cxxPlatform)
          throws NoSuchBuildTargetException {
        BuildRule extension = getExtension(pythonPlatform, cxxPlatform);
        SourcePath output = new BuildTargetSourcePath(extension.getBuildTarget());
        return PythonPackageComponents.of(
            ImmutableMap.of(module, output),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableMap.<Path, SourcePath>of(),
            ImmutableSet.<SourcePath>of(),
            Optional.of(false));
      }

      @Override
      public SharedNativeLinkTarget getNativeLinkTarget(final PythonPlatform pythonPlatform) {
        return new SharedNativeLinkTarget() {

          @Override
          public BuildTarget getBuildTarget() {
            return BuildTarget.builder(params.getBuildTarget())
                .addFlavors(pythonPlatform.getFlavor())
                .build();
          }

          @Override
          public Iterable<? extends NativeLinkable> getSharedNativeLinkTargetDeps(
              CxxPlatform cxxPlatform) {
            return FluentIterable.from(params.getDeclaredDeps().get())
                .filter(NativeLinkable.class)
                .append(
                    ruleResolver.getRuleOptionalWithType(
                        pythonPlatform.getCxxLibrary().get().getBuildTarget(),
                        NativeLinkable.class).get());
          }

          @Override
          public String getSharedNativeLinkTargetLibraryName(CxxPlatform cxxPlatform) {
            return getExtensionName(params.getBuildTarget());
          }

          @Override
          public NativeLinkableInput getSharedNativeLinkTargetInput(CxxPlatform cxxPlatform)
              throws NoSuchBuildTargetException {
            return NativeLinkableInput.builder()
                .addAllArgs(getExtensionArgs(params, ruleResolver, pathResolver, cxxPlatform, args))
                .addAllFrameworks(args.frameworks.or(ImmutableSortedSet.<FrameworkPath>of()))
                .build();
          }

        };
      }

    };
  }

  @Override
  public BuildRuleType getBuildRuleType() {
    return TYPE;
  }

  @Override
  public Iterable<BuildTarget> findDepsForTargetFromConstructorArgs(
      BuildTarget buildTarget,
      Function<Optional<String>, Path> cellRoots,
      Arg constructorArg) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();

    for (PythonPlatform pythonPlatform : pythonPlatforms.getValues()) {
      deps.addAll(pythonPlatform.getCxxLibrary().asSet());
    }

    return deps.build();
  }

  @SuppressFieldNotInitialized
  public static class Arg extends CxxConstructorArg {
    public Optional<String> baseModule;
  }

}
