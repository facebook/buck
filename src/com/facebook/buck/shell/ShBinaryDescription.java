/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.shell;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

public class ShBinaryDescription implements Description<ShBinaryDescriptionArg> {

  @Override
  public Class<ShBinaryDescriptionArg> getConstructorArgType() {
    return ShBinaryDescriptionArg.class;
  }

  @Override
  public ShBinary createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      ShBinaryDescriptionArg args) {
    return new ShBinary(
        buildTarget,
        context.getCellPathResolver(),
        context.getProjectFilesystem(),
        params,
        args.getMain(),
        args.getResources());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractShBinaryDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getMain();

    ImmutableSet<SourcePath> getResources();
  }
}
