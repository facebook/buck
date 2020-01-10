/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.step.fs.WriteFileStep;
import com.facebook.buck.util.zip.ZipCompressionLevel;
import com.facebook.buck.zip.ZipStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Creates a "test_config.properties" file to be included for additional configuration during
 * Android unit tests
 */
public class UnitTestOptions extends ModernBuildRule<UnitTestOptions.Impl> {

  public UnitTestOptions(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      ImmutableMap<String, String> properties) {
    super(buildTarget, projectFilesystem, ruleFinder, new Impl(properties));
  }

  static class Impl implements Buildable {
    @AddToRuleKey private final ImmutableMap<String, String> properties;

    @AddToRuleKey private final OutputPath src = new OutputPath(Paths.get("src"));

    // Nested in a src folder for easier management of the output jar and file being within the
    // same parent directory
    @AddToRuleKey
    private final OutputPath output =
        new OutputPath(Paths.get("src", "com", "android", "tools", "test_config.properties"));

    @AddToRuleKey
    private final OutputPath outputJar = new OutputPath(Paths.get("unit_test_options.jar"));

    private Impl(ImmutableMap<String, String> properties) {
      this.properties = properties;
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {

      ImmutableList.Builder<Step> steps = ImmutableList.builder();
      Path outputPath = outputPathResolver.resolvePath(output);
      Path srcPath = outputPathResolver.resolvePath(src);
      Path jarPath = outputPathResolver.resolvePath(outputJar);

      steps.add(MkdirStep.of(buildCellPathFactory.from(outputPath.getParent())));

      StringBuilder fileContents = new StringBuilder();
      for (ImmutableMap.Entry<String, String> entry : properties.entrySet()) {
        fileContents.append(entry.getKey()).append("=").append(entry.getValue()).append('\n');
      }

      steps.add(new WriteFileStep(filesystem, fileContents.toString(), outputPath, false));

      steps.add(RmStep.of(BuildCellRelativePath.of(jarPath)));
      steps.add(
          new ZipStep(
              filesystem, jarPath, ImmutableSet.of(), false, ZipCompressionLevel.NONE, srcPath));

      return steps.build();
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().outputJar);
  }
}
