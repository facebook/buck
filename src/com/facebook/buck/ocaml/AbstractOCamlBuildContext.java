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

import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.CxxSource;
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.UnflavoredBuildTarget;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
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

@Value.Immutable
@BuckStyleImmutable
abstract class AbstractOCamlBuildContext implements RuleKeyAppendable {
  static final String OCAML_COMPILED_BYTECODE_DIR = "bc";
  static final String OCAML_COMPILED_DIR = "opt";
  private static final String OCAML_GENERATED_SOURCE_DIR = "gen";

  static final Path DEFAULT_OCAML_INTEROP_INCLUDE_DIR = Paths.get("/usr/local/lib/ocaml");

  public abstract BuildTarget getBuildTarget();
  public abstract boolean isLibrary();
  public abstract List<String> getFlags();
  public abstract List<Path> getInput();
  public abstract List<String> getIncludes();
  public abstract NativeLinkableInput getLinkableInput();
  public abstract List<OCamlLibrary> getOCamlInput();
  public abstract CxxPreprocessorInput getCxxPreprocessorInput();
  public abstract List<String> getBytecodeIncludes();
  public abstract ImmutableSortedSet<BuildRule> getCompileDeps();
  public abstract ImmutableSortedSet<BuildRule> getBytecodeCompileDeps();
  public abstract ImmutableSortedSet<BuildRule> getBytecodeLinkDeps();

  public abstract Optional<Path> getOcamlDepTool();
  public abstract Optional<Path> getOcamlCompiler();
  public abstract Optional<Path> getOcamlDebug();
  public abstract Optional<Path> getYaccCompiler();
  public abstract Optional<Path> getLexCompiler();
  public abstract Optional<Path> getOcamlBytecodeCompiler();

  protected abstract List<String> getCFlags();
  protected abstract Optional<String> getOCamlInteropIncludesDir();
  protected abstract List<String> getLdFlags();

  public ImmutableList<Path> getCInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.ext(OCamlCompilables.OCAML_C))
        .toSet()
        .asList();
  }

  public ImmutableList<Path> getLexInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.ext(OCamlCompilables.OCAML_MLL))
        .toSet()
        .asList();
  }

  public ImmutableList<Path> getYaccInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.ext(OCamlCompilables.OCAML_MLY))
        .toSet()
        .asList();
  }

  public ImmutableList<Path> getMLInput() {
    return FluentIterable.from(getInput())
        .filter(OCamlUtil.ext(OCamlCompilables.OCAML_ML, OCamlCompilables.OCAML_MLI))
        .append(getLexOutput(getLexInput()))
        .append(getYaccOutput(getYaccInput()))
        .toSet()
        .asList();
  }

  private static Path getArchiveOutputPath(UnflavoredBuildTarget target) {
    return BuildTargets.getGenPath(
        target,
        "%s/lib" + target.getShortName() + OCamlCompilables.OCAML_CMXA);
  }

  private static Path getArchiveBytecodeOutputPath(UnflavoredBuildTarget target) {
    return BuildTargets.getGenPath(
        target,
        "%s/lib" + target.getShortName() + OCamlCompilables.OCAML_CMA);
  }

  public Path getOutput() {
    return getOutputPath(getBuildTarget(), isLibrary());
  }

  public static Path getOutputPath(BuildTarget target, boolean isLibrary) {
    UnflavoredBuildTarget plainTarget = target.getUnflavoredBuildTarget();
    if (isLibrary) {
      return getArchiveOutputPath(plainTarget);
    } else {
      return BuildTargets.getScratchPath(
          plainTarget,
          "%s/" + plainTarget.getShortName() + ".opt");
    }
  }

  public Path getBytecodeOutput() {
    UnflavoredBuildTarget plainTarget = getBuildTarget().getUnflavoredBuildTarget();
    if (isLibrary()) {
      return getArchiveBytecodeOutputPath(plainTarget);
    } else {
      return BuildTargets.getScratchPath(
          plainTarget,
          "%s/" + plainTarget.getShortName());
    }
  }

  public Path getGeneratedSourceDir() {
    return getOutput().getParent().resolve(OCAML_GENERATED_SOURCE_DIR);
  }

  public Path getCompileOutputDir() {
    return getCompileOutputDir(getBuildTarget(), isLibrary());
  }

  public static Path getCompileOutputDir(BuildTarget buildTarget, boolean isLibrary) {
    return getOutputPath(buildTarget, isLibrary).getParent().resolve(
        OCAML_COMPILED_DIR);
  }

  public Path getCompileBytecodeOutputDir() {
    return getOutput().getParent().resolve(OCAML_COMPILED_BYTECODE_DIR);
  }

  public Path getCOutput(Path cSrc) {
    String inputFileName = cSrc.getFileName().toString();
    String outputFileName = inputFileName
        .replaceFirst(
            OCamlCompilables.OCAML_C_REGEX,
            OCamlCompilables.OCAML_O);
    return getCompileOutputDir().resolve(outputFileName);
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
    for (Path mlFile : getMLInput()) {
      Path parent = mlFile.getParent();
      if (parent != null) {
        includeDirs.add(parent.toString());
      }
    }

    if (!excludeDeps) {
      includeDirs.addAll(isBytecode ? this.getBytecodeIncludes() : this.getIncludes());
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

  protected FluentIterable<Path> getLexOutput(Iterable<Path> lexInputs) {
    return FluentIterable.from(lexInputs)
        .transform(
            new Function<Path, Path>() {
              @Override
              public Path apply(Path lexInput) {
                return getGeneratedSourceDir().resolve(
                    lexInput.getFileName().toString().replaceFirst(
                        OCamlCompilables.OCAML_MLL_REGEX,
                        OCamlCompilables.OCAML_ML));
              }
            });
  }

  protected FluentIterable<Path> getYaccOutput(Iterable<Path> yaccInputs) {
    return FluentIterable.from(yaccInputs)
        .transformAndConcat(
            new Function<Path, Iterable<? extends Path>>() {
              @Override
              public Iterable<? extends Path> apply(Path yaccInput) {
                String yaccFileName = yaccInput.getFileName().toString();
                return ImmutableList.of(
                    getGeneratedSourceDir().resolve(
                        yaccFileName.replaceFirst(
                            OCamlCompilables.OCAML_MLY_REGEX,
                            OCamlCompilables.OCAML_ML)),
                    getGeneratedSourceDir().resolve(
                        yaccFileName.replaceFirst(
                            OCamlCompilables.OCAML_MLY_REGEX,
                            OCamlCompilables.OCAML_MLI)));
              }
            });
  }

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) {
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

    for (Path headerMap : cxxPreprocessorInput.getHeaderMaps()) {
      compileFlags.add("-ccopt", "-I" + headerMap.toString());
    }

    for (Path includes : cxxPreprocessorInput.getIncludeRoots()) {
      compileFlags.add("-ccopt", "-I" + includes.toString());
    }

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
