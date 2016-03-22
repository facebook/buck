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
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.MoreIterables;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;

import org.immutables.value.Value;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * OCaml build context
 *
 * OCaml has two build modes, "native" (ocamlopt) and "bytecode" (ocamlc), and that terminology is
 * used throughout this file -- not to be confused with the "native" terminology used in
 * com.facebook.buck.cxx.NativeLinkableInput.
 */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractOCamlBuildContext implements RuleKeyAppendable {
  static final String OCAML_COMPILED_BYTECODE_DIR = "bc";
  static final String OCAML_COMPILED_DIR = "opt";
  private static final String OCAML_GENERATED_SOURCE_DIR = "gen";

  static final Path DEFAULT_OCAML_INTEROP_INCLUDE_DIR = Paths.get("/usr/local/lib/ocaml");

  public abstract UnflavoredBuildTarget getBuildTarget();
  public abstract ProjectFilesystem getProjectFilesystem();
  public abstract SourcePathResolver getSourcePathResolver();

  public abstract boolean isLibrary();
  public abstract List<String> getFlags();
  public abstract List<SourcePath> getInput();
  public abstract List<String> getNativeIncludes();
  public abstract List<String> getBytecodeIncludes();

  /**
   * Inputs for the native (ocamlopt) build
   */
  public abstract NativeLinkableInput getNativeLinkableInput();

  /**
   * Inputs for the bytecode (ocamlc) build
   */
  public abstract NativeLinkableInput getBytecodeLinkableInput();

  /**
   * Inputs for the C compiler (both builds)
   */
  public abstract NativeLinkableInput getCLinkableInput();

  public abstract List<OCamlLibrary> getOCamlInput();
  public abstract CxxPreprocessorInput getCxxPreprocessorInput();
  public abstract ImmutableSortedSet<BuildRule> getNativeCompileDeps();
  public abstract ImmutableSortedSet<BuildRule> getBytecodeCompileDeps();
  public abstract ImmutableSortedSet<BuildRule> getBytecodeLinkDeps();

  public abstract Optional<Tool> getOcamlDepTool();
  public abstract Optional<Tool> getOcamlCompiler();
  public abstract Optional<Tool> getOcamlDebug();
  public abstract Optional<Tool> getYaccCompiler();
  public abstract Optional<Tool> getLexCompiler();
  public abstract Optional<Tool> getOcamlBytecodeCompiler();

  protected abstract List<String> getCFlags();
  protected abstract Optional<String> getOCamlInteropIncludesDir();
  protected abstract List<String> getLdFlags();

  public ImmutableList<SourcePath> getCInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.sourcePathExt(getSourcePathResolver(), OCamlCompilables.OCAML_C))
        .toSet()
        .asList();
  }

  public ImmutableList<SourcePath> getLexInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.sourcePathExt(getSourcePathResolver(), OCamlCompilables.OCAML_MLL))
        .toSet()
        .asList();
  }

  public ImmutableList<SourcePath> getYaccInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.sourcePathExt(getSourcePathResolver(), OCamlCompilables.OCAML_MLY))
        .toSet()
        .asList();
  }

  public ImmutableList<SourcePath> getMLInput() {
    return FluentIterable.from(getInput())
        .filter(
            OCamlUtil.sourcePathExt(
                getSourcePathResolver(),
                OCamlCompilables.OCAML_ML,
                OCamlCompilables.OCAML_MLI))
        .append(getLexOutput(getLexInput()))
        .append(getYaccOutput(getYaccInput()))
        .toSet()
        .asList();
  }

  private static Path getArchiveNativeOutputPath(UnflavoredBuildTarget target) {
    return BuildTargets.getGenPath(
        BuildTarget.of(target),
        "%s/lib" + target.getShortName() + OCamlCompilables.OCAML_CMXA);
  }

  private static Path getArchiveBytecodeOutputPath(UnflavoredBuildTarget target) {
    return BuildTargets.getGenPath(
        BuildTarget.of(target),
        "%s/lib" + target.getShortName() + OCamlCompilables.OCAML_CMA);
  }

  public Path getNativeOutput() {
    return getNativeOutputPath(getBuildTarget(), isLibrary());
  }

  public static Path getNativeOutputPath(UnflavoredBuildTarget target, boolean isLibrary) {
    if (isLibrary) {
      return getArchiveNativeOutputPath(target);
    } else {
      return BuildTargets.getScratchPath(
          BuildTarget.of(target),
          "%s/" + target.getShortName() + ".opt");
    }
  }

  public Path getBytecodeOutput() { return getBytecodeOutputPath(getBuildTarget(), isLibrary()); }

  public static Path getBytecodeOutputPath(UnflavoredBuildTarget target, boolean isLibrary) {
    if (isLibrary) {
      return getArchiveBytecodeOutputPath(target);
    } else {
      return BuildTargets.getScratchPath(
          BuildTarget.of(target),
          "%s/" + target.getShortName());
    }
  }

  public Path getGeneratedSourceDir() {
    return getNativeOutput().getParent().resolve(OCAML_GENERATED_SOURCE_DIR);
  }

  public Path getCompileNativeOutputDir() {
    return getCompileNativeOutputDir(getBuildTarget(), isLibrary());
  }

  public static Path getCompileNativeOutputDir(
      UnflavoredBuildTarget buildTarget,
      boolean isLibrary) {
    return getNativeOutputPath(buildTarget, isLibrary).getParent().resolve(
        OCAML_COMPILED_DIR);
  }

  public Path getCompileBytecodeOutputDir() {
    return getNativeOutput().getParent().resolve(OCAML_COMPILED_BYTECODE_DIR);
  }

  public Path getCOutput(Path cSrc) {
    String inputFileName = cSrc.getFileName().toString();
    String outputFileName = inputFileName
        .replaceFirst(
            OCamlCompilables.OCAML_C_REGEX,
            OCamlCompilables.OCAML_O);
    return getCompileNativeOutputDir().resolve(outputFileName);
  }

  public Function<Path, Path> toCOutput() {
    return new Function<Path, Path>() {
      @Override
      public Path apply(Path input) {
        return getCOutput(input);
      }
    };
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
            Iterables.cycle(OCamlCompilables.OCAML_INCLUDE_FLAG),
            getIncludeDirectories(isBytecode, excludeDeps)));
  }

  public ImmutableList<String> getBytecodeIncludeFlags() {
    return ImmutableList.copyOf(
        MoreIterables.zipAndConcat(
            Iterables.cycle(OCamlCompilables.OCAML_INCLUDE_FLAG),
            getBytecodeIncludeDirectories()));
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
            new Function<SourcePath, SourcePath>() {
              @Override
              public SourcePath apply(SourcePath lexInput) {
                Path fileName = getSourcePathResolver().getAbsolutePath(lexInput).getFileName();
                Path out = getGeneratedSourceDir().resolve(
                    fileName.toString().replaceFirst(
                        OCamlCompilables.OCAML_MLL_REGEX,
                        OCamlCompilables.OCAML_ML));
                return new PathSourcePath(getProjectFilesystem(), out);
              }
            });
  }

  protected FluentIterable<SourcePath> getYaccOutput(Iterable<SourcePath> yaccInputs) {
    return FluentIterable.from(yaccInputs)
        .transformAndConcat(
            new Function<SourcePath, Iterable<? extends SourcePath>>() {
              @Override
              public Iterable<? extends SourcePath> apply(SourcePath yaccInput) {
                String yaccFileName = getSourcePathResolver()
                    .getAbsolutePath(yaccInput)
                    .getFileName()
                    .toString();

                ImmutableList.Builder<SourcePath> toReturn = ImmutableList.builder();

                toReturn.add(new PathSourcePath(
                    getProjectFilesystem(),
                    getGeneratedSourceDir().resolve(
                        yaccFileName.replaceFirst(
                            OCamlCompilables.OCAML_MLY_REGEX,
                            OCamlCompilables.OCAML_ML))));

                toReturn.add(new PathSourcePath(
                    getProjectFilesystem(),
                    getGeneratedSourceDir().resolve(
                        yaccFileName.replaceFirst(
                            OCamlCompilables.OCAML_MLY_REGEX,
                            OCamlCompilables.OCAML_MLI))));

                return toReturn.build();
              }
            });
  }

  @Override
  public RuleKeyBuilder appendToRuleKey(RuleKeyBuilder builder) {
    return builder
        .setReflectively("flags", getFlags())
        .setReflectively("input", getInput())
        .setReflectively("lexCompiler", getLexCompiler())
        .setReflectively("ocamlBytecodeCompiler", getOcamlBytecodeCompiler())
        .setReflectively("ocamlCompiler", getOcamlCompiler())
        .setReflectively("ocamlDebug", getOcamlDebug())
        .setReflectively("ocamlDepTool", getOcamlDepTool())
        .setReflectively("yaccCompiler", getYaccCompiler());
  }

  public ImmutableList<String> getCCompileFlags() {
    ImmutableList.Builder<String> compileFlags = ImmutableList.builder();

    CxxPreprocessorInput cxxPreprocessorInput = getCxxPreprocessorInput();

    compileFlags.addAll(
        MoreIterables.zipAndConcat(
            Iterables.cycle("-ccopt"),
            CxxHeaders.getArgs(
                cxxPreprocessorInput.getIncludes(),
                getSourcePathResolver(),
                Optional.<Function<Path, Path>>absent())));

    for (Path includes : cxxPreprocessorInput.getSystemIncludeRoots()) {
      compileFlags.add("-ccopt", "-isystem" + includes.toString());
    }

    for (String cFlag : cxxPreprocessorInput.getPreprocessorFlags().get(CxxSource.Type.C)) {
      compileFlags.add("-ccopt", cFlag);
    }

    return compileFlags.build();
  }

  private static ImmutableList<String> addPrefix(String prefix, Iterable<String> flags) {
    return ImmutableList.copyOf(
      MoreIterables.zipAndConcat(
        Iterables.cycle(prefix),
        flags));
  }

  public ImmutableList<String> getCommonCFlags() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    builder.addAll(addPrefix("-ccopt", getCFlags()));
    builder.add("-ccopt",
        "-isystem" +
            getOCamlInteropIncludesDir()
                .or(DEFAULT_OCAML_INTEROP_INCLUDE_DIR.toString()));
    return builder.build();
  }

  public ImmutableList<String> getCommonCLinkerFlags() {
    return addPrefix("-ccopt", getLdFlags());
  }

  public static OCamlBuildContext.Builder builder(OCamlBuckConfig config) {
    return OCamlBuildContext.builder()
        .setOcamlDepTool(config.getOCamlDepTool())
        .setOcamlCompiler(config.getOCamlCompiler())
        .setOcamlDebug(config.getOCamlDebug())
        .setYaccCompiler(config.getYaccCompiler())
        .setLexCompiler(config.getLexCompiler())
        .setOcamlBytecodeCompiler(config.getOCamlBytecodeCompiler())
        .setOCamlInteropIncludesDir(config.getOCamlInteropIncludesDir())
        .setCFlags(config.getCFlags())
        .setLdFlags(config.getLdFlags());
  }

}
