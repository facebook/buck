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

package com.facebook.buck.go;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.TouchStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class GoCompile extends AbstractBuildRuleWithDeclaredAndExtraDeps {
  @AddToRuleKey private final Tool compiler;
  @AddToRuleKey private final Tool assembler;
  @AddToRuleKey private final Tool packer;

  @AddToRuleKey(stringify = true)
  private final Path packageName;

  @AddToRuleKey private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableList<String> compilerFlags;
  @AddToRuleKey private final ImmutableList<String> assemblerFlags;
  @AddToRuleKey private final GoPlatform platform;
  @AddToRuleKey private final Optional<CGoCompileRules> cgoCompile;

  // TODO(mikekap): Make these part of the rule key.
  private final ImmutableList<Path> assemblerIncludeDirs;
  private final ImmutableMap<Path, Path> importPathMap;

  private final SymlinkTree symlinkTree;
  private final Path output;

  public GoCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      SymlinkTree symlinkTree,
      Path packageName,
      ImmutableMap<Path, Path> importPathMap,
      ImmutableSet<SourcePath> srcs,
      ImmutableList<String> compilerFlags,
      Tool compiler,
      ImmutableList<String> assemblerFlags,
      ImmutableList<Path> assemblerIncludeDirs,
      Tool assembler,
      Tool packer,
      GoPlatform platform,
      Optional<CGoCompileRules> cgoCompile) {
    super(buildTarget, projectFilesystem, params);
    this.importPathMap = importPathMap;
    this.srcs = srcs;
    this.symlinkTree = symlinkTree;
    this.packageName = packageName;
    this.compilerFlags = compilerFlags;
    this.compiler = compiler;
    this.assemblerFlags = assemblerFlags;
    this.assemblerIncludeDirs = assemblerIncludeDirs;
    this.assembler = assembler;
    this.packer = packer;
    this.platform = platform;
    this.output =
        BuildTargets.getGenPath(
            getProjectFilesystem(),
            getBuildTarget(),
            "%s/" + getBuildTarget().getShortName() + ".a");
    this.cgoCompile = cgoCompile;
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifact(output);

    ImmutableList.Builder<Path> extraLibsListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Path> compileSrcListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Path> headerSrcListBuilder = ImmutableList.builder();
    ImmutableList.Builder<Path> asmSrcListBuilder = ImmutableList.builder();
    for (SourcePath path : srcs) {
      Path srcPath = context.getSourcePathResolver().getAbsolutePath(path);
      String extension = MorePaths.getFileExtension(srcPath).toLowerCase();
      if (extension.equals("s")) {
        asmSrcListBuilder.add(srcPath);
      } else if (extension.equals("go")) {
        compileSrcListBuilder.add(srcPath);
      } else {
        headerSrcListBuilder.add(srcPath);
      }
    }

    // cgo output needs to be linked to final binary
    if (cgoCompile.isPresent()) {
      compileSrcListBuilder.addAll(
          cgoCompile
              .get()
              .getGeneratedGoSource()
              .stream()
              .map(input -> context.getSourcePathResolver().getAbsolutePath(input))
              .collect(ImmutableList.toImmutableList()));
      Path srcPath =
          context.getSourcePathResolver().getAbsolutePath(cgoCompile.get().getOutputBinary());
      extraLibsListBuilder.add(srcPath);
    }

    ImmutableList<Path> extraLibs = extraLibsListBuilder.build();
    ImmutableList<Path> compileSrcs = compileSrcListBuilder.build();
    ImmutableList<Path> headerSrcs = headerSrcListBuilder.build();
    ImmutableList<Path> asmSrcs = asmSrcListBuilder.build();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), output.getParent())));

    Optional<Path> asmHeaderPath;

    if (!asmSrcs.isEmpty()) {
      asmHeaderPath =
          Optional.of(
              BuildTargets.getScratchPath(
                      getProjectFilesystem(),
                      getBuildTarget(),
                      "%s/" + getBuildTarget().getShortName() + "__asm_hdr")
                  .resolve("go_asm.h"));

      steps.add(
          MkdirStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(),
                  getProjectFilesystem(),
                  asmHeaderPath.get().getParent())));
    } else {
      asmHeaderPath = Optional.empty();
    }

    boolean allowExternalReferences = !asmSrcs.isEmpty() || !extraLibs.isEmpty();

    if (compileSrcs.isEmpty()) {
      steps.add(new TouchStep(getProjectFilesystem(), output));
    } else {
      steps.add(
          new GoCompileStep(
              getBuildTarget(),
              getProjectFilesystem().getRootPath(),
              compiler.getEnvironment(context.getSourcePathResolver()),
              compiler.getCommandPrefix(context.getSourcePathResolver()),
              compilerFlags,
              packageName,
              compileSrcs,
              importPathMap,
              ImmutableList.of(symlinkTree.getRoot()),
              asmHeaderPath,
              allowExternalReferences,
              platform,
              output));
    }

    ImmutableList.Builder<Path> asmOutputs = ImmutableList.builder();
    if (!asmSrcs.isEmpty()) {
      Path asmIncludeDir =
          BuildTargets.getScratchPath(
              getProjectFilesystem(),
              getBuildTarget(),
              "%s/" + getBuildTarget().getShortName() + "__asm_includes");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), asmIncludeDir)));

      if (!headerSrcs.isEmpty()) {
        // TODO(mikekap): Allow header-map style input.
        for (Path header :
            Stream.of(headerSrcs, asmSrcs)
                .flatMap(ImmutableList::stream)
                .collect(ImmutableList.toImmutableList())) {
          steps.add(
              SymlinkFileStep.builder()
                  .setFilesystem(getProjectFilesystem())
                  .setExistingFile(header)
                  .setDesiredLink(asmIncludeDir.resolve(header.getFileName()))
                  .build());
        }
      }

      Path asmOutputDir =
          BuildTargets.getScratchPath(
              getProjectFilesystem(),
              getBuildTarget(),
              "%s/" + getBuildTarget().getShortName() + "__asm_compile");

      steps.addAll(
          MakeCleanDirectoryStep.of(
              BuildCellRelativePath.fromCellRelativePath(
                  context.getBuildCellRootPath(), getProjectFilesystem(), asmOutputDir)));

      for (Path asmSrc : asmSrcs) {
        Path outputPath =
            asmOutputDir.resolve(asmSrc.getFileName().toString().replaceAll("\\.[sS]$", ".o"));
        steps.add(
            new GoAssembleStep(
                getBuildTarget(),
                getProjectFilesystem().getRootPath(),
                assembler.getEnvironment(context.getSourcePathResolver()),
                assembler.getCommandPrefix(context.getSourcePathResolver()),
                assemblerFlags,
                asmSrc,
                ImmutableList.<Path>builder()
                    .addAll(assemblerIncludeDirs)
                    .add(asmHeaderPath.get().getParent())
                    .add(asmIncludeDir)
                    .build(),
                platform,
                outputPath));
        asmOutputs.add(outputPath);
      }
    }

    if (!asmSrcs.isEmpty() || !extraLibs.isEmpty()) {
      steps.add(
          new GoPackStep(
              getBuildTarget(),
              getProjectFilesystem().getRootPath(),
              packer.getEnvironment(context.getSourcePathResolver()),
              packer.getCommandPrefix(context.getSourcePathResolver()),
              GoPackStep.Operation.APPEND,
              Stream.of(extraLibs, asmOutputs.build())
                  .flatMap(ImmutableList::stream)
                  .collect(ImmutableList.toImmutableList()),
              output));
    }

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), output);
  }
}
