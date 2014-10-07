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
import com.facebook.buck.cxx.NativeLinkableInput;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePaths;
import com.facebook.buck.util.MoreIterables;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.annotation.Nullable;


public class OCamlBuildContext {
  private static final String OCAML_COMPILED_BYTECODE_DIR = "bc";
  private static final String OCAML_COMPILED_DIR = "opt";
  private static final String OCAML_GENERATED_SOURCE_DIR = "gen";

  static final Path DEFAULT_OCAML_BYTECODE_COMPILER =
      Paths.get("/usr/bin/ocamlc.opt");
  static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("/usr/bin/ocamlyacc");
  static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("/usr/bin/ocamllex.opt");
  static final Path DEFAULT_OCAML_COMPILER = Paths.get("/usr/bin/ocamlopt.opt");
  static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("/usr/bin/ocamldep.opt");

  @Nullable
  private Path ocamlDepTool;
  @Nullable
  private Path ocamlCompiler;
  @Nullable
  private Path ocamlBytecodeCompiler;
  private boolean isLibrary;
  @Nullable
  private ImmutableList<String> flags;
  @Nullable
  private Path output;
  @Nullable
  private Path bytecodeOutput;
  @Nullable
  private ImmutableList<SourcePath> input;
  @Nullable
  private ImmutableList<String> includes;
  @Nullable
  private NativeLinkableInput linkableInput;
  @Nullable
  private ImmutableList<OCamlLibrary> ocamlInput;
  @Nullable
  private Path yaccCompiler;
  @Nullable
  private Path lexCompiler;
  @Nullable
  private Path compileOutputDir;
  @Nullable
  private Path compileBytecodeOutputDir;
  @Nullable
  private Path generatedSourceDir;
  @Nullable
  private CxxPreprocessorInput cxxPreprocessorInput;
  @Nullable
  private ImmutableSet<Path> cInput;
  @Nullable
  private ImmutableSet<Path> lexInput;
  @Nullable
  private ImmutableSet<Path> yaccInputs;
  @Nullable
  private ImmutableSet<Path> mlInput;

  public static Path getArchiveOutputPath(BuildTarget target) {
    return BuildTargets.getGenPath(
        target,
        "%s/lib" + target.getShortNameOnly() + OCamlCompilables.OCAML_CMXA);
  }

  public static Path getArchiveBytecodeOutputPath(BuildTarget target) {
    return BuildTargets.getGenPath(
        target,
        "%s/lib" + target.getShortNameOnly() + OCamlCompilables.OCAML_CMA);
  }

  private static Path getOutputPath(BuildTarget target, boolean isLibrary) {
    if (isLibrary) {
      return getArchiveOutputPath(target);
    } else {
      return BuildTargets.getBinPath(target, "%s/" + target.getShortName() + ".opt");
    }
  }

  private static Path getBytecodeOutputPath(BuildTarget target, boolean isLibrary) {
    if (isLibrary) {
      return getArchiveBytecodeOutputPath(target);
    } else {
      return BuildTargets.getBinPath(target, "%s/" + target.getShortName());
    }
  }

  private static Path getGeneratedSourceDir(BuildTarget buildTarget, boolean isLibrary) {
    return getOutputPath(buildTarget, isLibrary).getParent().resolve(
        OCAML_GENERATED_SOURCE_DIR);
  }

  public static Path getCompileOutputDir(BuildTarget buildTarget, boolean isLibrary) {
    return getOutputPath(buildTarget, isLibrary).getParent().resolve(
        OCAML_COMPILED_DIR);
  }

  public static Path getCompileBytecodeOutputDir(BuildTarget buildTarget, boolean isLibrary) {
    return getOutputPath(buildTarget, isLibrary).getParent().resolve(
        OCAML_COMPILED_BYTECODE_DIR);
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

  public static Builder builder(OCamlBuckConfig config) {
    return new Builder(new OCamlBuildContext(), config);
  }

  public Path getOcamlDepTool() {
    return Preconditions.checkNotNull(ocamlDepTool);
  }

  public Path getOcamlCompiler() {
    return Preconditions.checkNotNull(ocamlCompiler);
  }

  public boolean isLibrary() {
    return isLibrary;
  }

  public ImmutableList<String> getFlags() {
    return Preconditions.checkNotNull(flags);
  }

  public Path getOutput() {
    return Preconditions.checkNotNull(output);
  }

  public Path getBytecodeOutput() {
    return Preconditions.checkNotNull(bytecodeOutput);
  }

  public ImmutableList<SourcePath> getInput() {
    return Preconditions.checkNotNull(input);
  }

  public ImmutableList<String> getIncludes() {
    return Preconditions.checkNotNull(includes);
  }

  public NativeLinkableInput getLinkableInput() {
    return Preconditions.checkNotNull(linkableInput);
  }

  public ImmutableList<OCamlLibrary> getOCamlInput() {
    return Preconditions.checkNotNull(ocamlInput);
  }

  public Path getYaccCompiler() {
    return Preconditions.checkNotNull(yaccCompiler);
  }

  public Path getLexCompiler() {
    return Preconditions.checkNotNull(lexCompiler);
  }

  public Path getCompileOutputDir() {
    return Preconditions.checkNotNull(compileOutputDir);
  }

  public Path getGeneratedSourceDir() {
    return Preconditions.checkNotNull(generatedSourceDir);
  }

  public CxxPreprocessorInput getCxxPreprocessorInput() {
    return Preconditions.checkNotNull(cxxPreprocessorInput);
  }

  public Path getCompileBytecodeOutputDir() {
    return Preconditions.checkNotNull(compileBytecodeOutputDir);
  }

  public Path getOcamlBytecodeCompiler() {
    return Preconditions.checkNotNull(ocamlBytecodeCompiler);
  }

  public ImmutableList<String> getIncludeFlags(boolean excludeDeps) {
    Preconditions.checkNotNull(mlInput);
    ImmutableList.Builder<String> includeFlagsBuilder = ImmutableList.builder();

    ImmutableSet.Builder<String> includeDirs = ImmutableSet.builder();
    for (Path mlFile : mlInput) {
      Path parent = mlFile.getParent();
      if (parent != null) {
        includeDirs.add(parent.toString());
      }
    }
    for (String includeDir : includeDirs.build()) {
      includeFlagsBuilder.add(OCamlCompilables.OCAML_INCLUDE_FLAG, includeDir);
    }

    if (!excludeDeps) {
      includeFlagsBuilder.addAll(
          MoreIterables.zipAndConcat(
              Iterables.cycle(OCamlCompilables.OCAML_INCLUDE_FLAG),
              this.getIncludes()
          )
      );
    }

    return includeFlagsBuilder.build();
  }

  public ImmutableList<String> getBytecodeIncludeFlags() {
    ImmutableList.Builder<String> flagBuilder = ImmutableList.builder();
    flagBuilder.addAll(getIncludeFlags(/* excludeDeps */ true));
    flagBuilder.add(
      OCamlCompilables.OCAML_INCLUDE_FLAG,
      getCompileBytecodeOutputDir().toString()
    );
    return flagBuilder.build();
  }

  protected FluentIterable<Path> getLexOutput(ImmutableSet<Path> lexInputs) {
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

  protected FluentIterable<Path> getYaccOutput(ImmutableSet<Path> yaccInputs) {
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

  public ImmutableList<Path> getCInput() {
    return Preconditions.checkNotNull(cInput).asList();
  }

  public ImmutableList<Path> getLexInput() {
    return Preconditions.checkNotNull(lexInput).asList();
  }

  public ImmutableList<Path> getYaccInput() {
    return Preconditions.checkNotNull(yaccInputs).asList();
  }

  public ImmutableList<Path> getMLInput() {
    return Preconditions.checkNotNull(mlInput).asList();
  }

  public RuleKey.Builder appendDetailsToRuleKey(RuleKey.Builder builder) {
    return builder
        .setInput("ocamlDepTool", getOcamlDepTool())
        .setInput("ocamlCompiler", getOcamlCompiler())
        .setInput("ocamlBytecodeCompiler", getOcamlBytecodeCompiler())
        .setInput("yaccCompiler", getYaccCompiler())
        .setInput("lexCompiler", getLexCompiler())
        .set("flags", getFlags());
  }

  public static class Builder {
    private final OCamlBuildContext context;

    private Builder(OCamlBuildContext context, OCamlBuckConfig config) {
      this.context = Preconditions.checkNotNull(context);
      context.ocamlDepTool = config.getOCamlDepTool().or(DEFAULT_OCAML_DEP_TOOL);
      context.ocamlCompiler = config.getOCamlCompiler().or(DEFAULT_OCAML_COMPILER);
      context.ocamlBytecodeCompiler = config.getOCamlBytecodeCompiler()
          .or(DEFAULT_OCAML_BYTECODE_COMPILER);
      context.yaccCompiler = config.getYaccCompiler()
          .or(DEFAULT_OCAML_YACC_COMPILER);
      context.lexCompiler = config.getLexCompiler().or(DEFAULT_OCAML_LEX_COMPILER);
    }

    Builder setFlags(ImmutableList<String> flags) {
      context.flags = Preconditions.checkNotNull(flags);
      return this;
    }

    Builder setInput(ImmutableList<SourcePath> input) {
      Preconditions.checkNotNull(
          context.getGeneratedSourceDir(),
          "You should initialize directories before the call to setInput");

      context.input = Preconditions.checkNotNull(input);
      FluentIterable<Path> inputPaths = FluentIterable.from(context.input)
          .transform(SourcePaths.TO_PATH);

      context.cInput = inputPaths.filter(OCamlUtil.ext(OCamlCompilables.OCAML_C)).toSet();
      context.lexInput = inputPaths.filter(OCamlUtil.ext(OCamlCompilables.OCAML_MLL)).toSet();
      context.yaccInputs = inputPaths.filter(OCamlUtil.ext(OCamlCompilables.OCAML_MLY)).toSet();
      context.mlInput = ImmutableSet.copyOf(
          Iterables.concat(
              inputPaths.filter(
                  OCamlUtil.ext(
                      OCamlCompilables.OCAML_ML,
                      OCamlCompilables.OCAML_MLI)),
              context.getLexOutput(Preconditions.checkNotNull(context.lexInput)),
              context.getYaccOutput(Preconditions.checkNotNull(context.yaccInputs))));
      return this;
    }

    Builder setIncludes(ImmutableList<String> includes) {
      context.includes = Preconditions.checkNotNull(includes);
      return this;
    }

    Builder setLinkableInput(NativeLinkableInput linkableInput) {
      context.linkableInput = Preconditions.checkNotNull(linkableInput);
      return this;
    }

    public Builder setOcamlInput(ImmutableList<OCamlLibrary> ocamlInput) {
      context.ocamlInput = ocamlInput;
      return this;
    }

    Builder setUpDirectories(BuildTarget buildTarget, boolean isLibrary) {
      context.isLibrary = isLibrary;
      Preconditions.checkNotNull(buildTarget);
      context.output = getOutputPath(buildTarget, isLibrary);
      context.bytecodeOutput = getBytecodeOutputPath(buildTarget, isLibrary);
      context.compileOutputDir = getCompileOutputDir(buildTarget, isLibrary);
      context.compileBytecodeOutputDir = getCompileBytecodeOutputDir(buildTarget, isLibrary);
      context.generatedSourceDir = getGeneratedSourceDir(buildTarget, isLibrary);
      return this;
    }

    public Builder setCxxPreprocessorInput(CxxPreprocessorInput cxxPreprocessorInputFromDeps) {
      context.cxxPreprocessorInput = Preconditions.checkNotNull(cxxPreprocessorInputFromDeps);
      return this;
    }

    OCamlBuildContext build() {
      Preconditions.checkNotNull(context.getOcamlDepTool());
      Preconditions.checkNotNull(context.getOcamlCompiler());
      Preconditions.checkNotNull(context.getOcamlBytecodeCompiler());
      Preconditions.checkNotNull(context.getYaccCompiler());
      Preconditions.checkNotNull(context.getLexCompiler());
      Preconditions.checkNotNull(context.getFlags());
      Preconditions.checkNotNull(context.getInput());
      Preconditions.checkNotNull(context.getIncludes());
      Preconditions.checkNotNull(context.getLinkableInput());
      Preconditions.checkNotNull(context.getOCamlInput());
      Preconditions.checkNotNull(context.getOutput());
      Preconditions.checkNotNull(context.getBytecodeOutput());
      Preconditions.checkNotNull(context.getCompileOutputDir());
      Preconditions.checkNotNull(context.getCompileBytecodeOutputDir());
      Preconditions.checkNotNull(context.getGeneratedSourceDir());
      return context;
    }

  }

}
