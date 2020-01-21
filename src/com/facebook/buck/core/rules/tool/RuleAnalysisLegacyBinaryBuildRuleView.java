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

package com.facebook.buck.core.rules.tool;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.OutputLabel;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.core.rules.actions.Action;
import com.facebook.buck.core.rules.impl.RuleAnalysisLegacyBuildRuleView;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.toolchain.tool.Tool;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.util.Optional;

/**
 * As {@link RuleAnalysisLegacyBuildRuleView}, but also implements {@link BinaryBuildRule} so that
 * this rule can be executable (with {@code buck run}, in the legacy graph, etc)
 */
public class RuleAnalysisLegacyBinaryBuildRuleView extends RuleAnalysisLegacyBuildRuleView
    implements BinaryBuildRule {

  private final RunInfo runInfo;

  /**
   * Create an instance of {@link RuleAnalysisLegacyBinaryBuildRuleView}
   *
   * @param type the type of this {@link BuildRule}
   * @param buildTarget the {@link BuildTarget} of this analysis rule
   * @param action the action of the result for which we want to provide the {@link BuildRule} view
   * @param ruleResolver the current {@link BuildRuleResolver} to query dependent rules
   * @param projectFilesystem the filesystem
   * @param providerInfoCollection the providers returned by this build target
   * @throws IllegalStateException if {@code providerInfoCollection} does not contain an instance of
   *     {@link RunInfo}
   */
  public RuleAnalysisLegacyBinaryBuildRuleView(
      String type,
      BuildTarget buildTarget,
      Optional<Action> action,
      BuildRuleResolver ruleResolver,
      ProjectFilesystem projectFilesystem,
      ProviderInfoCollection providerInfoCollection) {
    super(type, buildTarget, action, ruleResolver, projectFilesystem, providerInfoCollection);
    this.runInfo =
        providerInfoCollection
            .get(RunInfo.PROVIDER)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Attempted to create an executable rule view without a RunInfo provider"));
  }

  @Override
  public Tool getExecutableCommand(OutputLabel outputLabel) {
    return RunInfoLegacyTool.of(runInfo);
  }
}
