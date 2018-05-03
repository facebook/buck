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

import com.facebook.buck.android.exopackage.ExopackageInstaller;
import com.facebook.buck.android.exopackage.ExopackagePathAndHash;
import com.facebook.buck.android.exopackage.ResourcesExoHelper;
import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.InstallTrigger;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.ImmutableSortedSet;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.SortedSet;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** Installs exopackage resource files to the device/devices. */
public class ExopackageResourcesInstaller extends AbstractBuildRule {
  @AddToRuleKey private final InstallTrigger trigger;
  @AddToRuleKey private final ImmutableList<ExopackagePathAndHash> paths;
  @AddToRuleKey private final SourcePath manifestPath;
  @AddToRuleKey private final SourcePath deviceExoContents;

  private final Supplier<SortedSet<BuildRule>> depsSupplier;

  public ExopackageResourcesInstaller(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      SourcePathRuleFinder ruleFinder,
      Collection<ExopackagePathAndHash> paths,
      SourcePath manifestPath,
      SourcePath deviceExoContents) {
    super(buildTarget, projectFilesystem);
    this.trigger = new InstallTrigger(projectFilesystem);
    this.depsSupplier = BuildableSupport.buildDepsSupplier(this, ruleFinder);
    this.paths = ImmutableList.copyOf(paths);
    this.manifestPath = manifestPath;
    this.deviceExoContents = deviceExoContents;
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    return ImmutableList.of(
        new AbstractExecutionStep("installing_exo_resource_files") {
          @Override
          public StepExecutionResult execute(ExecutionContext context)
              throws IOException, InterruptedException {
            trigger.verify(context);
            SourcePathResolver resolver = buildContext.getSourcePathResolver();
            String packageName =
                AdbHelper.tryToExtractPackageNameFromManifest(
                    resolver.getAbsolutePath(manifestPath));
            ImmutableSortedMap<String, ImmutableSortedSet<Path>> contents =
                ExopackageDeviceDirectoryLister.deserializeDirectoryContentsForPackage(
                    getProjectFilesystem(),
                    resolver.getRelativePath(deviceExoContents),
                    packageName);
            context
                .getAndroidDevicesHelper()
                .get()
                .adbCallOrThrow(
                    "installing_exo_resource_files",
                    device -> {
                      ImmutableSortedSet<Path> presentFiles =
                          Preconditions.checkNotNull(contents.get(device.getSerialNumber()));
                      new ExopackageInstaller(
                              resolver, context, getProjectFilesystem(), packageName, device)
                          .installMissingFiles(
                              presentFiles,
                              ResourcesExoHelper.getFilesToInstall(
                                  ResourcesExoHelper.getResourceFilesByHash(
                                      resolver, getProjectFilesystem(), paths.stream())),
                              ExopackageInstaller.RESOURCES_TYPE);
                      return true;
                    },
                    true);
            return StepExecutionResults.SUCCESS;
          }
        });
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }
}
