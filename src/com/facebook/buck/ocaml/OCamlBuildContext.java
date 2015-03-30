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
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyAppendable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
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


public class OCamlBuildContext implements RuleKeyAppendable {
  static final String OCAML_COMPILED_BYTECODE_DIR = "bc";
  static final String OCAML_COMPILED_DIR = "opt";
  private static final String OCAML_GENERATED_SOURCE_DIR = "gen";

  static final Path DEFAULT_OCAML_BYTECODE_COMPILER =
      Paths.get("/usr/bin/ocamlc.opt");
  static final Path DEFAULT_OCAML_YACC_COMPILER = Paths.get("/usr/bin/ocamlyacc");
  static final Path DEFAULT_OCAML_LEX_COMPILER = Paths.get("/usr/bin/ocamllex.opt");
  static final Path DEFAULT_OCAML_COMPILER = Paths.get("/usr/bin/ocamlopt.opt");
  static final Path DEFAULT_OCAML_DEP_TOOL = Paths.get("/usr/bin/ocamldep.opt");
  static final Path DEFAULT_OCAML_DEBUG = Paths.get("/usr/bin/ocamldebug");
  static final Path DEFAULT_OCAML_INTEROP_INCLUDE_DIR = Paths.get("/usr/local/lib/ocaml");

  @Nullable
  private OCamlBuckConfig config;
  @Nullable
  private Path ocamlDepTool;
  @Nullable
  private Path ocamlCompiler;
  @Nullable
  private Path ocamlBytecodeCompiler;
  @Nullable
  private Path ocamlDebug;
  private boolean isLibrary;
  @Nullable
  private ImmutableList<String> flags;
  @Nullable
  private Path output;
  @Nullable
  private Path bytecodeOutput;
  @Nullable
  private ImmutableList<Path> input;
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
  @Nullable
  private ImmutableList<String> bytecodeIncludes;

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

  public static Path getBytecodeOutputPath(BuildTarget target, boolean isLibrary) {
    UnflavoredBuildTarget plainTarget = target.getUnflavoredBuildTarget();
    if (isLibrary) {
      return getArchiveBytecodeOutputPath(plainTarget);
    } else {
      return BuildTargets.getScratchPath(
          plainTarget,
          "%s/" + plainTarget.getShortName());
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

  public static Builder builder(OCamlBuckConfig config, SourcePathResolver resolver) {
    return new Builder(new OCamlBuildContext(), config, resolver);
  }

  public Path getOcamlDepTool() {
    return Preconditions.checkNotNull(ocamlDepTool);
  }

  public Path getOcamlCompiler() {
    return Preconditions.checkNotNull(ocamlCompiler);
  }

  public Path getOcamlDebug() {
    return Preconditions.checkNotNull(ocamlDebug);
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

  public ImmutableList<Path> getInput() {
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

  public ImmutableList<String> getIncludeDirectories(boolean isBytecode, boolean excludeDeps) {
    Preconditions.checkNotNull(mlInput);

    ImmutableSet.Builder<String> includeDirs = ImmutableSet.builder();
    for (Path mlFile : mlInput) {
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
    Preconditions.checkNotNull(mlInput);
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

  @Override
  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder, String key) {
    return builder
        .setReflectively(key + ".flags", getFlags())
        .setReflectively(key + ".input", getInput())
        .setReflectively(key + ".lexCompiler", getLexCompiler())
        .setReflectively(key + ".ocamlBytecodeCompiler", getOcamlBytecodeCompiler())
        .setReflectively(key + ".ocamlCompiler", getOcamlCompiler())
        .setReflectively(key + ".ocamlDebug", getOcamlDebug())
        .setReflectively(key + ".ocamlDepTool", getOcamlDepTool())
        .setReflectively(key + ".yaccCompiler", getYaccCompiler());
  }

  public ImmutableList<String> getBytecodeIncludes() {
    return Preconditions.checkNotNull(bytecodeIncludes);
  }

  public ImmutableList<String> getCCompileFlags() {
    ImmutableList.Builder<String> compileFlags = ImmutableList.builder();

    CxxPreprocessorInput cxxPreprocessorInput = getCxxPreprocessorInput();

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
    builder.addAll(addPrefix("-ccopt", Preconditions.checkNotNull(config).getCFlags()));
    builder.add("-ccopt",
        "-isystem" +
            config.getOCamlInteropIncludesDir()
                .or(DEFAULT_OCAML_INTEROP_INCLUDE_DIR.toString()));
    return builder.build();
  }

  public ImmutableList<String> getCommonCLinkerFlags() {
    Preconditions.checkNotNull(config);
    return addPrefix("-ccopt",
        Iterables.concat(
          config.getCLinkerFlags(),
          addPrefix("-Xlinker", config.getLdFlags())));
  }


  public static class Builder {
    private final OCamlBuildContext context;
    private final SourcePathResolver resolver;

    private Builder(
        OCamlBuildContext context,
        OCamlBuckConfig config,
        SourcePathResolver resolver) {
      this.context = context;
      this.resolver = resolver;
      context.config = config;
      context.ocamlDepTool = config.getOCamlDepTool().or(DEFAULT_OCAML_DEP_TOOL);
      context.ocamlCompiler = config.getOCamlCompiler().or(DEFAULT_OCAML_COMPILER);
      context.ocamlBytecodeCompiler = config.getOCamlBytecodeCompiler()
          .or(DEFAULT_OCAML_BYTECODE_COMPILER);
      context.ocamlDebug = config.getOCamlDebug().or(DEFAULT_OCAML_DEBUG);
      context.yaccCompiler = config.getYaccCompiler()
          .or(DEFAULT_OCAML_YACC_COMPILER);
      context.lexCompiler = config.getLexCompiler().or(DEFAULT_OCAML_LEX_COMPILER);
    }

    Builder setFlags(ImmutableList<String> flags) {
      context.flags = flags;
      return this;
    }

    Builder setInput(ImmutableList<SourcePath> input) {
      Preconditions.checkNotNull(
          context.getGeneratedSourceDir(),
          "You should initialize directories before the call to setInput");

      FluentIterable<Path> inputPaths = FluentIterable.from(input)
          .transform(resolver.getPathFunction());

      context.input = inputPaths.toList();
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
      context.includes = includes;
      return this;
    }

    Builder setLinkableInput(NativeLinkableInput linkableInput) {
      context.linkableInput = linkableInput;
      return this;
    }

    public Builder setOcamlInput(ImmutableList<OCamlLibrary> ocamlInput) {
      context.ocamlInput = ocamlInput;
      return this;
    }

    Builder setUpDirectories(BuildTarget buildTarget, boolean isLibrary) {
      context.isLibrary = isLibrary;
      context.output = getOutputPath(buildTarget, isLibrary);
      context.bytecodeOutput = getBytecodeOutputPath(buildTarget, isLibrary);
      context.compileOutputDir = getCompileOutputDir(buildTarget, isLibrary);
      context.compileBytecodeOutputDir = getCompileBytecodeOutputDir(buildTarget, isLibrary);
      context.generatedSourceDir = getGeneratedSourceDir(buildTarget, isLibrary);
      return this;
    }

    public Builder setCxxPreprocessorInput(CxxPreprocessorInput cxxPreprocessorInputFromDeps) {
      context.cxxPreprocessorInput = cxxPreprocessorInputFromDeps;
      return this;
    }

    OCamlBuildContext build() {
      return context;
    }

    public Builder setBytecodeIncludes(ImmutableList<String> bytecodeIncludes) {
      context.bytecodeIncludes = bytecodeIncludes;
      return this;
    }
  }

}
