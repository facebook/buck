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

package com.facebook.buck.features.python;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.RuleArg;

/**
 * A rule for specifying Python test runners. This rule does nothing except to propagate the path to
 * a test runner.
 */
public class PythonTestRunnerDescription
    implements DescriptionWithTargetGraph<PythonTestRunnerDescriptionArg> {

  @Override
  public Class<PythonTestRunnerDescriptionArg> getConstructorArgType() {
    return PythonTestRunnerDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PythonTestRunnerDescriptionArg args) {
    return new PythonTestRunner(
        buildTarget, context.getProjectFilesystem(), args.getSrc(), args.getMainModule());
  }

  @RuleArg
  interface AbstractPythonTestRunnerDescriptionArg extends BuildRuleArg {
    SourcePath getSrc();

    String getMainModule();
  }
}
