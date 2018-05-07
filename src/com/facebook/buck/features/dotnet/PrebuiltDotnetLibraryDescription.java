/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.sourcepath.resolver.impl.DefaultSourcePathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.SourcePathRuleFinder;
import org.immutables.value.Value;

public class PrebuiltDotnetLibraryDescription
    implements Description<PrebuiltDotnetLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltDotnetLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltDotnetLibraryDescriptionArg.class;
  }

  @Override
  public BuildRule createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltDotnetLibraryDescriptionArg args) {
    SourcePathResolver pathResolver =
        DefaultSourcePathResolver.from(new SourcePathRuleFinder(context.getBuildRuleResolver()));
    return new PrebuiltDotnetLibrary(
        buildTarget, context.getProjectFilesystem(), params, pathResolver, args.getAssembly());
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltDotnetLibraryDescriptionArg extends CommonDescriptionArg {
    SourcePath getAssembly();
  }
}
