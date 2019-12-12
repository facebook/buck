/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.jvm.java;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.BuildOutputInitializer;
import com.facebook.buck.core.rules.attr.InitializableFromDisk;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.jvm.core.CalculateAbi;
import com.facebook.buck.jvm.core.DefaultJavaAbiInfo;
import com.facebook.buck.jvm.core.JavaAbiInfo;
import com.facebook.buck.jvm.java.abi.AbiGenerationMode;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import java.io.IOException;

/** Calculates Class ABI. */
public class CalculateClassAbi extends ModernBuildRule<CalculateClassAbi.Impl>
    implements CalculateAbi, InitializableFromDisk<Object> {

  private final BuildOutputInitializer<Object> buildOutputInitializer;
  private final JavaAbiInfo javaAbiInfo;

  public CalculateClassAbi(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      SourcePath binaryJar,
      AbiGenerationMode compatibilityMode) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            binaryJar,
            compatibilityMode,
            projectFilesystem,
            String.format("%s-abi.jar", buildTarget.getShortName())));
    this.javaAbiInfo = new DefaultJavaAbiInfo(getSourcePathToOutput());
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  public static CalculateClassAbi of(
      BuildTarget target,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      SourcePath library) {
    return of(target, ruleFinder, projectFilesystem, library, AbiGenerationMode.CLASS);
  }

  public static CalculateClassAbi of(
      BuildTarget target,
      SourcePathRuleFinder ruleFinder,
      ProjectFilesystem projectFilesystem,
      SourcePath library,
      AbiGenerationMode compatibilityMode) {
    return new CalculateClassAbi(target, projectFilesystem, ruleFinder, library, compatibilityMode);
  }

  /** CalculateClassAbi's buildable implementation required by MBR */
  static class Impl implements Buildable {

    @AddToRuleKey private final SourcePath binaryJar;
    /**
     * Controls whether we strip out things that are intentionally not included in other forms of
     * ABI generation, so that we can still detect bugs by binary comparison.
     */
    @AddToRuleKey private final AbiGenerationMode compatibilityMode;

    @AddToRuleKey private final OutputPath output;

    Impl(
        SourcePath binaryJar,
        AbiGenerationMode compatibilityMode,
        ProjectFilesystem projectFilesystem,
        String outputFileName) {
      this.binaryJar = binaryJar;
      this.compatibilityMode = compatibilityMode;
      this.output = new OutputPath(projectFilesystem.getPath(outputFileName));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      return ImmutableList.of(
          new CalculateClassAbiStep(
              filesystem,
              buildContext.getSourcePathResolver().getAbsolutePath(binaryJar),
              outputPathResolver.resolvePath(output),
              compatibilityMode));
    }
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return getSourcePath(getBuildable().output);
  }

  @Override
  public JavaAbiInfo getAbiInfo() {
    return javaAbiInfo;
  }

  @Override
  public void invalidateInitializeFromDiskState() {
    javaAbiInfo.invalidate();
  }

  @Override
  public Object initializeFromDisk(SourcePathResolverAdapter pathResolver) throws IOException {
    // Warm up the jar contents. We just wrote the thing, so it should be in the filesystem cache
    javaAbiInfo.load(pathResolver);
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return buildOutputInitializer;
  }
}
