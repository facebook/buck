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

package com.facebook.buck.features.lua;

import com.facebook.buck.core.cell.resolver.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.impl.WriteStringTemplateRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.cxx.AbstractCxxLibrary;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxLinkOptions;
import com.facebook.buck.cxx.CxxLinkableEnhancer;
import com.facebook.buck.cxx.CxxPreprocessAndCompile;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.CxxSourceRuleFactory;
import com.facebook.buck.cxx.toolchain.CxxBuckConfig;
import com.facebook.buck.cxx.toolchain.CxxPlatform;
import com.facebook.buck.cxx.toolchain.PicType;
import com.facebook.buck.cxx.toolchain.linker.Linker;
import com.facebook.buck.cxx.toolchain.linker.Linkers;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTarget;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkTargetMode;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkable;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.file.WriteFile;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.Escaper;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** {@link Starter} implementation which builds a starter as a native executable. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractNativeExecutableStarter implements Starter, NativeLinkTarget {

  private static final String NATIVE_STARTER_CXX_SOURCE = "native-starter.cpp.in";

  abstract ProjectFilesystem getProjectFilesystem();

  abstract BuildTarget getBaseTarget();

  abstract BuildRuleParams getBaseParams();

  abstract ActionGraphBuilder getActionGraphBuilder();

  abstract SourcePathResolver getPathResolver();

  abstract SourcePathRuleFinder getRuleFinder();

  abstract CellPathResolver getCellPathResolver();

  abstract LuaPlatform getLuaPlatform();

  abstract CxxBuckConfig getCxxBuckConfig();

  abstract BuildTarget getTarget();

  abstract Path getOutput();

  abstract String getMainModule();

  abstract Optional<BuildTarget> getNativeStarterLibrary();

  abstract Optional<Path> getRelativeModulesDir();

  abstract Optional<Path> getRelativePythonModulesDir();

  abstract Optional<Path> getRelativeNativeLibsDir();

  private String getNativeStarterCxxSourceTemplate() {
    try {
      return Resources.toString(
          Resources.getResource(AbstractNativeExecutableStarter.class, NATIVE_STARTER_CXX_SOURCE),
          Charsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private CxxSource getNativeStarterCxxSource() {
    BuildRule rule =
        getActionGraphBuilder()
            .computeIfAbsent(
                getBaseTarget().withAppendedFlavors(InternalFlavor.of("native-starter-cxx-source")),
                target -> {
                  BuildTarget templateTarget =
                      getBaseTarget()
                          .withAppendedFlavors(
                              InternalFlavor.of("native-starter-cxx-source-template"));
                  WriteFile templateRule =
                      getActionGraphBuilder()
                          .addToIndex(
                              new WriteFile(
                                  templateTarget,
                                  getProjectFilesystem(),
                                  getNativeStarterCxxSourceTemplate(),
                                  BuildTargetPaths.getGenPath(
                                      getProjectFilesystem(),
                                      templateTarget,
                                      "%s/native-starter.cpp.in"),
                                  /* executable */ false));

                  Path output =
                      BuildTargetPaths.getGenPath(
                          getProjectFilesystem(), target, "%s/native-starter.cpp");
                  return WriteStringTemplateRule.from(
                      getProjectFilesystem(),
                      getBaseParams(),
                      getRuleFinder(),
                      target,
                      output,
                      templateRule.getSourcePathToOutput(),
                      ImmutableMap.of(
                          "MAIN_MODULE",
                          Escaper.escapeAsPythonString(getMainModule()),
                          "MODULES_DIR",
                          getRelativeModulesDir().isPresent()
                              ? Escaper.escapeAsPythonString(
                                  getRelativeModulesDir().get().toString())
                              : "NULL",
                          "PY_MODULES_DIR",
                          getRelativePythonModulesDir().isPresent()
                              ? Escaper.escapeAsPythonString(
                                  getRelativePythonModulesDir().get().toString())
                              : "NULL",
                          "EXT_SUFFIX",
                          Escaper.escapeAsPythonString(
                              getLuaPlatform().getCxxPlatform().getSharedLibraryExtension())),
                      /* executable */ false);
                });

    return CxxSource.of(
        CxxSource.Type.CXX,
        Preconditions.checkNotNull(rule.getSourcePathToOutput()),
        ImmutableList.of());
  }

  private ImmutableList<CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform, Iterable<? extends CxxPreprocessorDep> deps) {
    ImmutableList.Builder<CxxPreprocessorInput> inputs = ImmutableList.builder();
    inputs.addAll(
        CxxPreprocessables.getTransitiveCxxPreprocessorInput(
            cxxPlatform,
            getActionGraphBuilder(),
            FluentIterable.from(deps).filter(BuildRule.class)));
    for (CxxPreprocessorDep dep :
        Iterables.filter(deps, Predicates.not(BuildRule.class::isInstance))) {
      inputs.add(dep.getCxxPreprocessorInput(cxxPlatform, getActionGraphBuilder()));
    }
    return inputs.build();
  }

  public Iterable<? extends AbstractCxxLibrary> getNativeStarterDeps() {
    return ImmutableList.of(
        getNativeStarterLibrary().isPresent()
            ? getActionGraphBuilder()
                .getRuleWithType(getNativeStarterLibrary().get(), AbstractCxxLibrary.class)
            : getLuaPlatform().getLuaCxxLibrary(getActionGraphBuilder()));
  }

  private NativeLinkableInput getNativeLinkableInput() {
    Iterable<? extends AbstractCxxLibrary> nativeStarterDeps = getNativeStarterDeps();
    ImmutableMap<CxxPreprocessAndCompile, SourcePath> objects =
        CxxSourceRuleFactory.of(
                getProjectFilesystem(),
                getBaseTarget(),
                getActionGraphBuilder(),
                getPathResolver(),
                getRuleFinder(),
                getCxxBuckConfig(),
                getLuaPlatform().getCxxPlatform(),
                ImmutableList.<CxxPreprocessorInput>builder()
                    .add(
                        CxxPreprocessorInput.builder()
                            .putAllPreprocessorFlags(
                                CxxSource.Type.CXX,
                                getNativeStarterLibrary().isPresent()
                                    ? ImmutableList.of()
                                    : StringArg.from("-DBUILTIN_NATIVE_STARTER"))
                            .build())
                    .addAll(
                        getTransitiveCxxPreprocessorInput(
                            getLuaPlatform().getCxxPlatform(), nativeStarterDeps))
                    .build(),
                ImmutableMultimap.of(),
                Optional.empty(),
                Optional.empty(),
                PicType.PDC,
                Optional.empty())
            .requirePreprocessAndCompileRules(
                ImmutableMap.of("native-starter.cpp", getNativeStarterCxxSource()));
    return NativeLinkableInput.builder()
        .addAllArgs(
            getRelativeNativeLibsDir().isPresent()
                ? StringArg.from(
                    Linkers.iXlinker(
                        "-rpath",
                        String.format(
                            "%s/%s",
                            getLuaPlatform()
                                .getCxxPlatform()
                                .getLd()
                                .resolve(getActionGraphBuilder())
                                .origin(),
                            getRelativeNativeLibsDir().get().toString())))
                : ImmutableList.of())
        .addAllArgs(SourcePathArg.from(objects.values()))
        .build();
  }

  @Override
  public SourcePath build() {
    BuildTarget linkTarget = getTarget();
    CxxLink linkRule =
        getActionGraphBuilder()
            .addToIndex(
                CxxLinkableEnhancer.createCxxLinkableBuildRule(
                    getCxxBuckConfig(),
                    getLuaPlatform().getCxxPlatform(),
                    getProjectFilesystem(),
                    getActionGraphBuilder(),
                    getPathResolver(),
                    getRuleFinder(),
                    linkTarget,
                    Linker.LinkType.EXECUTABLE,
                    Optional.empty(),
                    getOutput(),
                    ImmutableList.of(),
                    Linker.LinkableDepType.SHARED,
                    CxxLinkOptions.of(),
                    getNativeStarterDeps(),
                    Optional.empty(),
                    Optional.empty(),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    getNativeLinkableInput(),
                    Optional.empty(),
                    getCellPathResolver()));
    return linkRule.getSourcePathToOutput();
  }

  @Override
  public BuildTarget getBuildTarget() {
    return getBaseTarget();
  }

  @Override
  public NativeLinkTargetMode getNativeLinkTargetMode(CxxPlatform cxxPlatform) {
    return NativeLinkTargetMode.executable();
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkTargetDeps(
      CxxPlatform cxxPlatform, ActionGraphBuilder graphBuilder) {
    return getNativeStarterDeps();
  }

  @Override
  public NativeLinkableInput getNativeLinkTargetInput(
      CxxPlatform cxxPlatform,
      ActionGraphBuilder graphBuilder,
      SourcePathResolver pathResolver,
      SourcePathRuleFinder ruleFinder) {
    return getNativeLinkableInput();
  }

  @Override
  public Optional<Path> getNativeLinkTargetOutputPath(CxxPlatform cxxPlatform) {
    return Optional.of(getOutput());
  }
}
