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

package com.facebook.buck.rules.modern;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.rulekey.AddToRuleKey;
import com.facebook.buck.core.rulekey.DefaultFieldInputs;
import com.facebook.buck.core.rulekey.DefaultFieldSerialization;
import com.facebook.buck.core.rulekey.ExcludeFromRuleKey;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolverAdapter;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.external.model.ParsedArgs;
import com.facebook.buck.external.utils.BuildStepsRetriever;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.modern.model.BuildableCommand;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.buildables.BuildableCommandExecutionStep;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;
import java.util.function.Supplier;

/**
 * Buildable with an external action that is decoupled from buck's core and can be executed in a
 * separate process.
 */
public abstract class BuildableWithExternalAction implements Buildable {

  @AddToRuleKey private final boolean shouldExecuteInSeparateProcess;
  @AddToRuleKey private final Tool javaRuntimeLauncher;

  @ExcludeFromRuleKey(
      reason = "path to external actions binary is not a part of a rule key",
      serialization = DefaultFieldSerialization.class,
      inputs = DefaultFieldInputs.class)
  private final Supplier<SourcePath> externalActionsSourcePathSupplier;

  public BuildableWithExternalAction(
      boolean shouldExecuteInSeparateProcess,
      Tool javaRuntimeLauncher,
      Supplier<SourcePath> externalActionsSourcePathSupplier) {
    this.shouldExecuteInSeparateProcess = shouldExecuteInSeparateProcess;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.externalActionsSourcePathSupplier = externalActionsSourcePathSupplier;
  }

  @Override
  @SuppressWarnings("unchecked")
  public final ImmutableList<Step> getBuildSteps(
      BuildContext buildContext,
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildCellRelativePathFactory buildCellPathFactory) {
    BuildableCommand buildableCommand =
        getBuildableCommand(filesystem, outputPathResolver, buildContext);
    if (shouldExecuteInSeparateProcess) {
      SourcePathResolverAdapter sourcePathResolver = buildContext.getSourcePathResolver();
      return ImmutableList.of(
          new BuildableCommandExecutionStep(
              getBuildableCommand(filesystem, outputPathResolver, buildContext),
              filesystem,
              javaRuntimeLauncher.getCommandPrefix(sourcePathResolver),
              () ->
                  sourcePathResolver.getRelativePath(
                      filesystem, externalActionsSourcePathSupplier.get())));
    }
    String externalActionClassName = buildableCommand.getExternalActionClass();
    try {
      Class<? extends ExternalAction> externalActionClass =
          (Class<? extends ExternalAction>) Class.forName(externalActionClassName);
      ImmutableList<IsolatedStep> steps =
          BuildStepsRetriever.getStepsForBuildable(
              ParsedArgs.of(externalActionClass, buildableCommand));
      return steps.stream().map(step -> (Step) step).collect(ImmutableList.toImmutableList());
    } catch (ClassNotFoundException e) {
      throw new IllegalArgumentException(
          String.format("Cannot find external actions class: %s", externalActionClassName), e);
    }
  }

  /**
   * Returns a {@link BuildableCommand} that can be used to reconstruct the build steps associated
   * with this buildable.
   */
  public abstract BuildableCommand getBuildableCommand(
      ProjectFilesystem filesystem,
      OutputPathResolver outputPathResolver,
      BuildContext buildContext);
}
