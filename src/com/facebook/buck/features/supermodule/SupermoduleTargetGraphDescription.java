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

package com.facebook.buck.features.supermodule;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleCreationContextWithTargetGraph;
import com.facebook.buck.core.rules.BuildRuleParams;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.rules.modern.OutputPath;
import com.facebook.buck.util.zip.collect.OnDuplicateEntry;
import com.facebook.buck.versions.VersionPropagator;
import org.immutables.value.Value;

/** Description of {@code SupermoduleTargetGraph} build rule. */
public class SupermoduleTargetGraphDescription
    implements DescriptionWithTargetGraph<SupermoduleTargetGraphDescriptionArg>,
        VersionPropagator<SupermoduleTargetGraphDescriptionArg> {

  @Override
  public Class<SupermoduleTargetGraphDescriptionArg> getConstructorArgType() {
    return SupermoduleTargetGraphDescriptionArg.class;
  }

  @Override
  public SupermoduleTargetGraph createBuildRule(
      BuildRuleCreationContextWithTargetGraph context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      SupermoduleTargetGraphDescriptionArg args) {

    return new SupermoduleTargetGraph(
        context.getActionGraphBuilder(),
        buildTarget,
        context.getProjectFilesystem(),
        new OutputPath(args.getOut()));
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  /** Abstract class of args for the {@code SupermoduleTargetGraph} build rule. */
  @RuleArg
  interface AbstractSupermoduleTargetGraphDescriptionArg extends BuildRuleArg {
    @Value.Default
    default String getOut() {
      return getName() + ".json";
    }

    @Value.Default
    default OnDuplicateEntry getOnDuplicateEntry() {
      return OnDuplicateEntry.OVERWRITE;
    }
  }
}
