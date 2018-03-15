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

package com.facebook.buck.cli;

import com.facebook.buck.config.FakeBuckConfig;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractBuildRule;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.InstallTrigger;
import com.facebook.buck.rules.KnownBuildRuleTypes;
import com.facebook.buck.rules.NoopInstallable;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Ordering;
import java.io.IOException;
import java.util.SortedSet;
import javax.annotation.Nullable;
import org.immutables.value.Value;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class InstallTriggerIntegrationTest {
  private static final String TRIGGER_TARGET = "//:install_trigger";
  private static final String NORMAL_TARGET = "//:normal_target";

  @Rule public TemporaryPaths tmpFolder = new TemporaryPaths(true);
  private ProjectWorkspace workspace;

  @Before
  public void setUp() throws InterruptedException, IOException {
    workspace =
        TestDataHelper.createProjectWorkspaceForScenario(this, "install_trigger", tmpFolder);
    workspace.setKnownBuildRuleTypesFactoryFactory(
        (processExecutor, pluginManager, sandboxExecutionStrategyFactory) ->
            cell ->
                KnownBuildRuleTypes.builder()
                    .addDescriptions(new InstallTriggerDescription())
                    .addDescriptions(new ExportFileDescription(FakeBuckConfig.builder().build()))
                    .build());
    workspace.setUp();
  }

  @Test
  public void testInstallTrigger() throws IOException {
    // Even without changes, the rule should always build locally.
    // Build it twice with buckd.
    workspace.runBuckdCommand("install", TRIGGER_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(TRIGGER_TARGET);
    workspace.getBuildLog().assertTargetBuiltLocally(NORMAL_TARGET);
    workspace.runBuckdCommand("install", TRIGGER_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(TRIGGER_TARGET);
    workspace.getBuildLog().assertTargetHadMatchingRuleKey(NORMAL_TARGET);
    // And once without buckd.
    workspace.runBuckCommand("install", TRIGGER_TARGET).assertSuccess();
    workspace.getBuildLog().assertTargetBuiltLocally(TRIGGER_TARGET);
    workspace.getBuildLog().assertTargetHadMatchingRuleKey(NORMAL_TARGET);
  }

  private static class InstallTriggerDescription
      implements Description<InstallTriggerDescriptionArg> {
    @Override
    public Class<InstallTriggerDescriptionArg> getConstructorArgType() {
      return InstallTriggerDescriptionArg.class;
    }

    @Override
    public BuildRule createBuildRule(
        BuildRuleCreationContext context,
        BuildTarget buildTarget,
        BuildRuleParams params,
        InstallTriggerDescriptionArg args) {
      return new InstallTriggerRule(
          buildTarget, context.getProjectFilesystem(), params.getBuildDeps());
    }

    private static class InstallTriggerRule extends AbstractBuildRule implements NoopInstallable {
      @AddToRuleKey private final InstallTrigger trigger;
      @AddToRuleKey private final ImmutableSortedSet<SourcePath> inputs;
      private SortedSet<BuildRule> buildDeps;

      public InstallTriggerRule(
          BuildTarget buildTarget,
          ProjectFilesystem projectFilesystem,
          SortedSet<BuildRule> buildDeps) {
        super(buildTarget, projectFilesystem);
        trigger = new InstallTrigger(projectFilesystem);
        inputs =
            buildDeps
                .stream()
                .map(BuildRule::getSourcePathToOutput)
                .collect(ImmutableSortedSet.toImmutableSortedSet(Ordering.natural()));
        this.buildDeps = buildDeps;
      }

      @Override
      public SortedSet<BuildRule> getBuildDeps() {
        return buildDeps;
      }

      @Override
      public ImmutableList<? extends Step> getBuildSteps(
          BuildContext context, BuildableContext buildableContext) {
        return ImmutableList.of(
            new AbstractExecutionStep("verify_trigger") {
              @Override
              public StepExecutionResult execute(ExecutionContext context) {
                trigger.verify(context);
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
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractInstallTriggerDescriptionArg extends HasDeclaredDeps {}
}
