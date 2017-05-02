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

package com.facebook.buck.jvm.java;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildOutputInitializer;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.InitializableFromDisk;
import com.facebook.buck.rules.OnDiskBuildInfo;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import javax.annotation.Nullable;

public class CompareAbis extends AbstractBuildRule
    implements SupportsInputBasedRuleKey, CalculateAbi, InitializableFromDisk<Object> {
  @AddToRuleKey private final SourcePath classAbi;
  @AddToRuleKey private final SourcePath sourceAbi;
  @AddToRuleKey private final JavaBuckConfig.SourceAbiVerificationMode verificationMode;

  private final Path outputPath;
  private final JarContentsSupplier outputPathContentsSupplier;

  public CompareAbis(
      BuildRuleParams params,
      SourcePathResolver resolver,
      SourcePath classAbi,
      SourcePath sourceAbi,
      JavaBuckConfig.SourceAbiVerificationMode verificationMode) {
    super(params);
    this.classAbi = classAbi;
    this.sourceAbi = sourceAbi;
    this.verificationMode = verificationMode;

    this.outputPath =
        BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s")
            .resolve(String.format("%s-abi.jar", getBuildTarget().getShortName()));

    outputPathContentsSupplier = new JarContentsSupplier(resolver, getSourcePathToOutput());
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    ProjectFilesystem filesystem = getProjectFilesystem();
    SourcePathResolver sourcePathResolver = context.getSourcePathResolver();

    Path classAbiPath = sourcePathResolver.getAbsolutePath(classAbi);
    Path sourceAbiPath = sourcePathResolver.getAbsolutePath(sourceAbi);
    return ImmutableList.of(
        MkdirStep.of(filesystem, outputPath.getParent()),
        DiffAbisStep.of(classAbiPath, sourceAbiPath, verificationMode),
        CopyStep.forFile(filesystem, classAbiPath, outputPath));
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return new ExplicitBuildTargetSourcePath(getBuildTarget(), outputPath);
  }

  @Override
  public Object initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) throws IOException {
    outputPathContentsSupplier.load();
    return new Object();
  }

  @Override
  public BuildOutputInitializer<Object> getBuildOutputInitializer() {
    return new BuildOutputInitializer<>(getBuildTarget(), this);
  }

  @Override
  public ImmutableSortedSet<SourcePath> getJarContents() {
    return outputPathContentsSupplier.get();
  }
}
