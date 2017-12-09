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

package com.facebook.buck.ocaml;

import com.facebook.buck.cxx.CxxHeaders;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.toolchain.Preprocessor;
import com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.AddsToRuleKey;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import org.immutables.value.Value;

/**
 * OCaml build context
 *
 * <p>OCaml has two build modes, "native" (ocamlopt) and "bytecode" (ocamlc), and that terminology
 * is used throughout this file -- not to be confused with the "native" terminology used in
 * com.facebook.buck.cxx.toolchain.nativelink.NativeLinkableInput.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractOcamlBuildContext implements AddsToRuleKey {
  static final String OCAML_COMPILED_BYTECODE_DIR = "bc";
  static final String OCAML_COMPILED_DIR = "opt";
  private static final String OCAML_GENERATED_SOURCE_DIR = "gen";

  static final Path DEFAULT_OCAML_INTEROP_INCLUDE_DIR = Paths.get("/usr/local/lib/ocaml");

  public abstract UnflavoredBuildTarget getBuildTarget();

  public abstract ProjectFilesystem getProjectFilesystem();

  public abstract SourcePathResolver getSourcePathResolver();

  public abstract boolean isLibrary();

  @AddToRuleKey
  public abstract List<Arg> getFlags();

  @AddToRuleKey
  public abstract List<SourcePath> getInput();

  public abstract List<String> getNativeIncludes();

  public abstract List<String> getBytecodeIncludes();

  /** Inputs for the native (ocamlopt) build */
  public abstract NativeLinkableInput getNativeLinkableInput();

  /** Inputs for the bytecode (ocamlc) build */
  public abstract NativeLinkableInput getBytecodeLinkableInput();

  /** Inputs for the C compiler (both builds) */
  public abstract NativeLinkableInput getCLinkableInput();

  public abstract List<OcamlLibrary> getOcamlInput();

  public abstract CxxPreprocessorInput getCxxPreprocessorInput();

  public abstract ImmutableSortedSet<BuildRule> getNativeCompileDeps();

  public abstract ImmutableSortedSet<BuildRule> getBytecodeCompileDeps();

  public abstract ImmutableSortedSet<BuildRule> getBytecodeLinkDeps();

  @AddToRuleKey
  public abstract Optional<Tool> getOcamlDepTool();

  @AddToRuleKey
  public abstract Optional<Tool> getOcamlCompiler();

  @AddToRuleKey
  public abstract Optional<Tool> getOcamlDebug();

  @AddToRuleKey
  public abstract Optional<Tool> getYaccCompiler();

  @AddToRuleKey
  public abstract Optional<Tool> getLexCompiler();

  @AddToRuleKey
  public abstract Optional<Tool> getOcamlBytecodeCompiler();

  protected abstract List<String> getCFlags();

  protected abstract Optional<String> getOcamlInteropIncludesDir();

  protected abstract List<String> getLdFlags();

  protected abstract Preprocessor getCPreprocessor();

  public ImmutableList<SourcePath> getCInput() {
    return getInput()
        .stream()
        .filter(OcamlUtil.sourcePathExt(getSourcePathResolver(), OcamlCompilables.OCAML_C))
        .distinct()
        .collect(ImmutableList.toImmutableList());
  }

  public ImmutableList<SourcePath> getLexInput() {
    return getInput()
        .stream()
        .filter(OcamlUtil.sourcePathExt(getSourcePathResolver(), OcamlCompilables.OCAML_MLL))
        .distinct()
        .collect(ImmutableList.toImmutableList());
  }

  public ImmutableList<SourcePath> getYaccInput() {
    return getInput()
        .stream()
        .filter(OcamlUtil.sourcePathExt(getSourcePathResolver(), OcamlCompilables.OCAML_MLY))
        .distinct()
        .collect(ImmutableList.toImmutableList());
  }

  public ImmutableList<SourcePath> getMLInput() {
    return FluentIterable.from(getInput())
        .filter(
            OcamlUtil.sourcePathExt(
                    getSourcePathResolver(),
                    OcamlCompilables.OCAML_ML,
                    OcamlCompilables.OCAML_RE,
                    OcamlCompilables.OCAML_MLI,
                    OcamlCompilables.OCAML_REI)
                ::test)
        .append(getLexOutput(getLexInput()))
        .append(getYaccOutput(getYaccInput()))
        .toSet()
        .asList();
  }

  private static Path getArchiveNativeOutputPath(
      UnflavoredBuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(
        filesystem,
        BuildTarget.of(target),
        "%s/lib" + target.getShortName() + OcamlCompilables.OCAML_CMXA);
  }

  private static Path getArchiveBytecodeOutputPath(
      UnflavoredBuildTarget target, ProjectFilesystem filesystem) {
    return BuildTargets.getGenPath(
        filesystem,
        BuildTarget.of(target),
        "%s/lib" + target.getShortName() + OcamlCompilables.OCAML_CMA);
  }

  public Path getNativeOutput() {
    return getNativeOutputPath(getBuildTarget(), getProjectFilesystem(), isLibrary());
  }

  public Path getNativePluginOutput() {
    UnflavoredBuildTarget target = getBuildTarget();
    return BuildTargets.getGenPath(
        getProjectFilesystem(),
        BuildTarget.of(target),
        "%s/lib" + target.getShortName() + OcamlCompilables.OCAML_CMXS);
  }

  public static Path getNativeOutputPath(
      UnflavoredBuildTarget target, ProjectFilesystem filesystem, boolean isLibrary) {
    if (isLibrary) {
      return getArchiveNativeOutputPath(target, filesystem);
    } else {
      return BuildTargets.getScratchPath(
          filesystem, BuildTarget.of(target), "%s/" + target.getShortName() + ".opt");
    }
  }

  public Path getBytecodeOutput() {
    return getBytecodeOutputPath(getBuildTarget(), getProjectFilesystem(), isLibrary());
  }

  public static Path getBytecodeOutputPath(
      UnflavoredBuildTarget target, ProjectFilesystem filesystem, boolean isLibrary) {
    if (isLibrary) {
      return getArchiveBytecodeOutputPath(target, filesystem);
    } else {
      return BuildTargets.getScratchPath(
          filesystem, BuildTarget.of(target), "%s/" + target.getShortName());
    }
  }

  public Path getGeneratedSourceDir() {
    return getNativeOutput().getParent().resolve(OCAML_GENERATED_SOURCE_DIR);
  }

  public Path getCompileNativeOutputDir() {
    return getCompileNativeOutputDir(getBuildTarget(), getProjectFilesystem(), isLibrary());
  }

  public static Path getCompileNativeOutputDir(
      UnflavoredBuildTarget buildTarget, ProjectFilesystem filesystem, boolean isLibrary) {
    return getNativeOutputPath(buildTarget, filesystem, isLibrary)
        .getParent()
        .resolve(OCAML_COMPILED_DIR);
  }

  public Path getCompileBytecodeOutputDir() {
    return getNativeOutput().getParent().resolve(OCAML_COMPILED_BYTECODE_DIR);
  }

  public Path getCOutput(Path cSrc) {
    String inputFileName = cSrc.getFileName().toString();
    String outputFileName =
        inputFileName.replaceFirst(OcamlCompilables.OCAML_C_REGEX, OcamlCompilables.OCAML_O);
    return getCompileNativeOutputDir().resolve(outputFileName);
  }

  public ImmutableList<String> getIncludeDirectories(boolean isBytecode, boolean excludeDeps) {
    ImmutableSet.Builder<String> includeDirs = ImmutableSet.builder();
    for (SourcePath mlFile : getMLInput()) {
      Path parent = getSourcePathResolver().getAbsolutePath(mlFile).getParent();
      if (parent != null) {
        includeDirs.add(parent.toString());
      }
    }

    if (!excludeDeps) {
      includeDirs.addAll(isBytecode ? this.getBytecodeIncludes() : this.getNativeIncludes());
    }

    return ImmutableList.copyOf(includeDirs.build());
  }

  public ImmutableList<String> getIncludeFlags(boolean isBytecode, boolean excludeDeps) {
    return ImmutableList.copyOf(
        MoreIterables.zipAndConcat(
            Iterables.cycle(OcamlCompilables.OCAML_INCLUDE_FLAG),
            getIncludeDirectories(isBytecode, excludeDeps)));
  }

  public ImmutableList<String> getBytecodeIncludeFlags() {
    return ImmutableList.copyOf(
        MoreIterables.zipAndConcat(
            Iterables.cycle(OcamlCompilables.OCAML_INCLUDE_FLAG), getBytecodeIncludeDirectories()));
  }

  public ImmutableList<String> getBytecodeIncludeDirectories() {
    ImmutableList.Builder<String> includesBuilder = ImmutableList.builder();
    includesBuilder.addAll(getIncludeDirectories(true, /* excludeDeps */ true));
    includesBuilder.add(getCompileBytecodeOutputDir().toString());
    return includesBuilder.build();
  }

  protected FluentIterable<SourcePath> getLexOutput(Iterable<SourcePath> lexInputs) {
    return FluentIterable.from(lexInputs)
        .transform(
            lexInput -> {
              Path fileName = getSourcePathResolver().getAbsolutePath(lexInput).getFileName();
              Path out =
                  getGeneratedSourceDir()
                      .resolve(
                          fileName
                              .toString()
                              .replaceFirst(
                                  OcamlCompilables.OCAML_MLL_REGEX, OcamlCompilables.OCAML_ML));
              return PathSourcePath.of(getProjectFilesystem(), out);
            });
  }

  protected FluentIterable<SourcePath> getYaccOutput(Iterable<SourcePath> yaccInputs) {
    return FluentIterable.from(yaccInputs)
        .transformAndConcat(
            yaccInput -> {
              String yaccFileName =
                  getSourcePathResolver().getAbsolutePath(yaccInput).getFileName().toString();

              ImmutableList.Builder<SourcePath> toReturn = ImmutableList.builder();

              toReturn.add(
                  PathSourcePath.of(
                      getProjectFilesystem(),
                      getGeneratedSourceDir()
                          .resolve(
                              yaccFileName.replaceFirst(
                                  OcamlCompilables.OCAML_MLY_REGEX, OcamlCompilables.OCAML_ML))));

              toReturn.add(
                  PathSourcePath.of(
                      getProjectFilesystem(),
                      getGeneratedSourceDir()
                          .resolve(
                              yaccFileName.replaceFirst(
                                  OcamlCompilables.OCAML_MLY_REGEX, OcamlCompilables.OCAML_MLI))));

              return toReturn.build();
            });
  }

  public ImmutableList<Arg> getCCompileFlags() {
    ImmutableList.Builder<Arg> compileFlags = ImmutableList.builder();

    CxxPreprocessorInput cxxPreprocessorInput = getCxxPreprocessorInput();

    compileFlags.addAll(
        StringArg.from(
            CxxHeaders.getArgs(
                cxxPreprocessorInput.getIncludes(),
                getSourcePathResolver(),
                Optional.empty(),
                getCPreprocessor())));

    for (Arg cFlag : cxxPreprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C)) {
      compileFlags.add(cFlag);
    }

    return compileFlags.build();
  }

  private static ImmutableList<String> addPrefix(String prefix, Iterable<String> flags) {
    return ImmutableList.copyOf(MoreIterables.zipAndConcat(Iterables.cycle(prefix), flags));
  }

  public ImmutableList<String> getCommonCFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(getCFlags());
    builder.add(
        "-isystem"
            + getOcamlInteropIncludesDir().orElse(DEFAULT_OCAML_INTEROP_INCLUDE_DIR.toString()));
    return builder.build();
  }

  public ImmutableList<String> getCommonCLinkerFlags() {
    return addPrefix("-ccopt", getLdFlags());
  }

  public static OcamlBuildContext.Builder builder(
      OcamlToolchain ocamlToolchain, OcamlBuckConfig config) {
    return OcamlBuildContext.builder()
        .setOcamlDepTool(config.getOcamlDepTool())
        .setOcamlCompiler(config.getOcamlCompiler())
        .setOcamlDebug(config.getOcamlDebug())
        .setYaccCompiler(config.getYaccCompiler())
        .setLexCompiler(config.getLexCompiler())
        .setOcamlBytecodeCompiler(config.getOcamlBytecodeCompiler())
        .setOcamlInteropIncludesDir(config.getOcamlInteropIncludesDir())
        .setCFlags(ocamlToolchain.getCFlags())
        .setLdFlags(ocamlToolchain.getLdFlags());
  }
}
