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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.DefaultSourcePathResolver;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import org.immutables.value.Value;

public class CxxPrecompiledHeaderDescription
    implements Description<CxxPrecompiledHeaderDescriptionArg>,
        VersionPropagator<CxxPrecompiledHeaderDescriptionArg> {

  @Override
  public Class<CxxPrecompiledHeaderDescriptionArg> getConstructorArgType() {
    return CxxPrecompiledHeaderDescriptionArg.class;
  }

  @Override
  public CxxPrecompiledHeaderTemplate createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      CxxPrecompiledHeaderDescriptionArg args) {
    BuildRuleResolver ruleResolver = context.getBuildRuleResolver();
    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(ruleResolver);
    SourcePathResolver pathResolver = DefaultSourcePathResolver.from(ruleFinder);
    return new CxxPrecompiledHeaderTemplate(
        buildTarget,
        context.getProjectFilesystem(),
        ruleResolver.getAllRules(args.getDeps()),
        args.getSrc(),
        pathResolver.getAbsolutePath(args.getSrc()));
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractCxxPrecompiledHeaderDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getSrc();
  }
}
