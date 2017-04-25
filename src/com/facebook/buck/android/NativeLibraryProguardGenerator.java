/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** This provides a way for android_binary rules to generate proguard config based on the */
public class NativeLibraryProguardGenerator extends AbstractBuildRule {
  public static final String OUTPUT_FORMAT = "%s/native-libs.pro";
  @AddToRuleKey private final ImmutableList<SourcePath> nativeLibsDirs;
  @AddToRuleKey private final BuildRule codeGenerator;

  @AddToRuleKey(stringify = true)
  private final Path outputPath;

  NativeLibraryProguardGenerator(
      BuildRuleParams buildRuleParams,
      SourcePathRuleFinder ruleFinder,
      ImmutableList<SourcePath> nativeLibsDirs,
      BuildRule codeGenerator) {
    super(
        buildRuleParams.copyAppendingExtraDeps(
            RichStream.from(ruleFinder.filterBuildRuleInputs(nativeLibsDirs))
                .concat(RichStream.of(codeGenerator))
                .toImmutableList()));
    this.nativeLibsDirs = nativeLibsDirs;
    this.codeGenerator = codeGenerator;
    this.outputPath =
        BuildTargets.getGenPath(
            buildRuleParams.getProjectFilesystem(), getBuildTarget(), OUTPUT_FORMAT);
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputPath);
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path outputDir = outputPath.getParent();
    buildableContext.recordArtifact(outputDir);
    return ImmutableList.<Step>builder()
        .addAll(MakeCleanDirectoryStep.of(getProjectFilesystem(), outputDir))
        .add(new RunConfigGenStep(context.getSourcePathResolver()))
        .build();
  }

  private class RunConfigGenStep extends ShellStep {
    private final SourcePathResolver pathResolver;

    RunConfigGenStep(SourcePathResolver sourcePathResolver) {
      super(getProjectFilesystem().getRootPath());
      this.pathResolver = sourcePathResolver;
    }

    @Override
    protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
      ImmutableList.Builder<Path> libPaths = ImmutableList.builder();
      ProjectFilesystem rootFilesystem = getProjectFilesystem();
      try {
        for (SourcePath path : nativeLibsDirs) {
          ProjectFilesystem pathFilesystem = pathResolver.getFilesystem(path);
          pathFilesystem.walkRelativeFileTree(
              pathResolver.getRelativePath(path),
              new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                  libPaths.add(
                      rootFilesystem.relativize(pathFilesystem.getPathForRelativePath(file)));
                  return super.visitFile(file, attrs);
                }
              });
        }
      } catch (IOException e) {
        throw new RuntimeException(e);
      }

      String executableCommand;
      try {
        executableCommand = new ExecutableMacroExpander().expand(pathResolver, codeGenerator);
      } catch (MacroException e) {
        throw new RuntimeException(e);
      }

      return ImmutableList.<String>builder()
          .addAll(Splitter.on(' ').split(executableCommand))
          .add(pathResolver.getRelativePath(getSourcePathToOutput()).toString())
          .addAll(libPaths.build().stream().map(Path::toString)::iterator)
          .build();
    }

    @Override
    public String getShortName() {
      return "generate_native_libs_proguard_config";
    }
  }
}
