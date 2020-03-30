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
import com.facebook.buck.jvm.core.JavaAbis;
import com.facebook.buck.rules.modern.BuildCellRelativePathFactory;
import com.facebook.buck.rules.modern.Buildable;
import com.facebook.buck.rules.modern.ModernBuildRule;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.rules.modern.OutputPathResolver;
import com.facebook.buck.step.Step;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;

/** Calculates a Source ABI by copying the source ABI output from the library rule into a JAR. */
public class CalculateSourceAbiFromLibraryTarget
    extends ModernBuildRule<CalculateSourceAbiFromLibraryTarget.Impl>
    implements CalculateAbi, InitializableFromDisk<Object> {

  private final BuildOutputInitializer<Object> buildOutputInitializer;
  private final JavaAbiInfo javaAbiInfo;

  public CalculateSourceAbiFromLibraryTarget(
      SourcePath binaryJar,
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder) {
    super(
        buildTarget,
        projectFilesystem,
        ruleFinder,
        new Impl(
            binaryJar,
            JavaAbis.getLibraryTarget(buildTarget),
            projectFilesystem,
            String.format("%s-abi.jar", buildTarget.getShortName())));
    this.javaAbiInfo = new DefaultJavaAbiInfo(getSourcePathToOutput());
    this.buildOutputInitializer = new BuildOutputInitializer<>(buildTarget, this);
  }

  /** Buildable implementation required by MBR */
  static class Impl implements Buildable {

    @AddToRuleKey private final BuildTarget libraryTarget;
    @AddToRuleKey private final SourcePath binaryJar;
    @AddToRuleKey private final OutputPath output;

    Impl(
        SourcePath binaryJar,
        BuildTarget libraryTarget,
        ProjectFilesystem projectFilesystem,
        String outputFileName) {
      this.binaryJar = binaryJar;
      this.libraryTarget = libraryTarget;
      this.output = new OutputPath(projectFilesystem.getPath(outputFileName));
    }

    @Override
    public ImmutableList<Step> getBuildSteps(
        BuildContext buildContext,
        ProjectFilesystem filesystem,
        OutputPathResolver outputPathResolver,
        BuildCellRelativePathFactory buildCellPathFactory) {
      JarParameters jarParameters =
          JarParameters.builder()
              .setJarPath(outputPathResolver.resolvePath(output))
              .setEntriesToJar(
                  ImmutableSortedSet.of(
                      JavaAbis.getTmpGenPathForSourceAbi(filesystem, libraryTarget)))
              .setHashEntries(true)
              .build();
      return ImmutableList.of(new JarDirectoryStep(filesystem, jarParameters));
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
