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

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.SourcePathRuleFinder;
import com.facebook.buck.core.rules.attr.HasRuntimeDeps;
import com.facebook.buck.core.rules.common.InstallTrigger;
import com.facebook.buck.core.rules.impl.AbstractBuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import java.util.SortedSet;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Installs a non-exopackage apk. */
public class AndroidBinaryNonExoInstaller extends AbstractBuildRule implements HasRuntimeDeps {
  @AddToRuleKey private final InstallTrigger trigger;
  @AddToRuleKey private final HasInstallableApk apk;

  private final Supplier<ImmutableSortedSet<BuildRule>> depsSupplier;

  protected AndroidBinaryNonExoInstaller(
      BuildTarget buildTarget, ProjectFilesystem projectFilesystem, HasInstallableApk apk) {
    super(buildTarget, projectFilesystem);
    Preconditions.checkState(!apk.getApkInfo().getExopackageInfo().isPresent());
    this.trigger = new InstallTrigger(projectFilesystem);
    this.apk = apk;
    this.depsSupplier = MoreSuppliers.memoize(() -> ImmutableSortedSet.of((BuildRule) apk));
  }

  @Override
  public SortedSet<BuildRule> getBuildDeps() {
    return depsSupplier.get();
  }

  @Override
  public ImmutableList<? extends Step> getBuildSteps(
      BuildContext buildContext, BuildableContext buildableContext) {
    return ImmutableList.of(
        new AbstractExecutionStep("install_apk") {
          @Override
          public StepExecutionResult execute(ExecutionContext context) throws InterruptedException {
            trigger.verify(context);
            boolean result =
                context
                    .getAndroidDevicesHelper()
                    .get()
                    .installApk(buildContext.getSourcePathResolver(), apk, false, true, null);
            return result ? StepExecutionResults.SUCCESS : StepExecutionResults.ERROR;
          }
        });
  }

  @Override
  public boolean isCacheable() {
    return false;
  }

  @Nullable
  @Override
  public SourcePath getSourcePathToOutput() {
    return null;
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    return getBuildDeps().stream().map(BuildRule::getBuildTarget);
  }
}
