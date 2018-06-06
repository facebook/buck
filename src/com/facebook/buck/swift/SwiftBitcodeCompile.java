package com.facebook.buck.swift;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.description.BuildRuleParams;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.impl.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.core.sourcepath.ExplicitBuildTargetSourcePath;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class SwiftBitcodeCompile extends AbstractBuildRuleWithDeclaredAndExtraDeps {

  private Path outputPath;
  private Tool swiftCompiler;
  private String moduleName;
  private Path bitcodePath;

  public SwiftBitcodeCompile(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      Tool swiftCompiler,
      SwiftCompile moduleRule,
      String filename) {
    super(
        buildTarget,
        projectFilesystem,
        new BuildRuleParams(
            () -> ImmutableSortedSet.of(moduleRule),
            ImmutableSortedSet::of,
            ImmutableSortedSet.of()));
    this.swiftCompiler = swiftCompiler;
    this.moduleName = moduleRule.getModuleName();
    this.bitcodePath = moduleRule.getBitcodePath(filename);
    this.outputPath =
        BuildTargets.getGenPath(projectFilesystem, buildTarget, "%s").resolve(filename + ".o");
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ImmutableList.Builder<String> compilerCommand = ImmutableList.builder();
    compilerCommand.addAll(swiftCompiler.getCommandPrefix(context.getSourcePathResolver()));
    compilerCommand.add(
        "-module-name",
        this.moduleName,
        "-c",
        this.bitcodePath.toString(),
        "-o",
        this.outputPath.toString());

    ProjectFilesystem projectFilesystem = getProjectFilesystem();
    return ImmutableList.of(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), outputPath.getParent())),
        new SwiftCompileStep(
            projectFilesystem.getRootPath(), ImmutableMap.of(), compilerCommand.build()));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(getBuildTarget(), outputPath);
  }
}
