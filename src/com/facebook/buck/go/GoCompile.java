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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableProperties;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SymlinkTree;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.SymlinkFileStep;
import com.facebook.buck.step.fs.TouchStep;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.Optional;

public class GoCompile extends AbstractBuildRule {
  @AddToRuleKey private final Tool compiler;
  @AddToRuleKey private final Tool assembler;
  @AddToRuleKey private final Tool packer;

  @AddToRuleKey(stringify = true)
  private final Path packageName;

  @AddToRuleKey private final ImmutableSet<SourcePath> srcs;
  @AddToRuleKey private final ImmutableList<String> compilerFlags;
  @AddToRuleKey private final ImmutableList<String> assemblerFlags;
  @AddToRuleKey private final GoPlatform platform;
  // TODO(mikekap): Make these part of the rule key.
  private final ImmutableList<Path> assemblerIncludeDirs;
  private final ImmutableMap<Path, Path> importPathMap;

  private final SymlinkTree symlinkTree;
  private final Path output;

  public GoCompile(
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
      GoPlatform platform) {
    super(params);
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
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    buildableContext.recordArtifact(output);

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

    ImmutableList<Path> compileSrcs = compileSrcListBuilder.build();
    ImmutableList<Path> headerSrcs = headerSrcListBuilder.build();
    ImmutableList<Path> asmSrcs = asmSrcListBuilder.build();

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.add(MkdirStep.of(getProjectFilesystem(), output.getParent()));

    Optional<Path> asmHeaderPath;

    if (!asmSrcs.isEmpty()) {
      asmHeaderPath =
          Optional.of(
              BuildTargets.getScratchPath(
                      getProjectFilesystem(),
                      getBuildTarget(),
                      "%s/" + getBuildTarget().getShortName() + "__asm_hdr")
                  .resolve("go_asm.h"));

      steps.add(MkdirStep.of(getProjectFilesystem(), asmHeaderPath.get().getParent()));
    } else {
      asmHeaderPath = Optional.empty();
    }

    boolean allowExternalReferences = !asmSrcs.isEmpty();

    if (compileSrcs.isEmpty()) {
      steps.add(new TouchStep(getProjectFilesystem(), output));
    } else {
      steps.add(
          new GoCompileStep(
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

    if (!asmSrcs.isEmpty()) {
      Path asmIncludeDir =
          BuildTargets.getScratchPath(
              getProjectFilesystem(),
              getBuildTarget(),
              "%s/" + getBuildTarget().getShortName() + "__asm_includes");
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), asmIncludeDir));

      if (!headerSrcs.isEmpty()) {
        // TODO(mikekap): Allow header-map style input.
        for (Path header : FluentIterable.from(headerSrcs).append(asmSrcs)) {
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
      steps.addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), asmOutputDir));

      ImmutableList.Builder<Path> asmOutputs = ImmutableList.builder();
      for (Path asmSrc : asmSrcs) {
        Path outputPath =
            asmOutputDir.resolve(asmSrc.getFileName().toString().replaceAll("\\.[sS]$", ".o"));
        steps.add(
            new GoAssembleStep(
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

      steps.add(
          new GoPackStep(
              getProjectFilesystem().getRootPath(),
              packer.getEnvironment(context.getSourcePathResolver()),
              packer.getCommandPrefix(context.getSourcePathResolver()),
              GoPackStep.Operation.APPEND,
              asmOutputs.build(),
              output));
    }

    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), output);
  }

  @Override
  public BuildableProperties getProperties() {
    return new BuildableProperties(BuildableProperties.Kind.LIBRARY);
  }
}
