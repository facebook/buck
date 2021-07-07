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

package com.facebook.buck.features.alias;

import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.versions.VersionPropagator;

/** {@code Description} class which represents the {@code alias} rule */
public class AliasDescription extends AbstractAliasDescription<AliasDescriptionArg>
    implements VersionPropagator<AliasDescriptionArg> {

  @Override
  public Class<AliasDescriptionArg> getConstructorArgType() {
    return AliasDescriptionArg.class;
  }

  @Override
  public BuildTarget resolveActualBuildTarget(AliasDescriptionArg arg) {
    return arg.getActual();
  }

  /** {@code RuleArg} for the {@code alias} rule */
  @RuleArg
  interface AbstractAliasDescriptionArg extends BuildRuleArg {
    BuildTarget getActual();
  }
}
