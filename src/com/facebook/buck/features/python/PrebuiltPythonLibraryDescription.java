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

package com.facebook.buck.features.python;

import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.Description;
import org.immutables.value.Value;

public class PrebuiltPythonLibraryDescription
    implements Description<PrebuiltPythonLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltPythonLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltPythonLibraryDescriptionArg.class;
  }

  @Override
  public PrebuiltPythonLibrary createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      PrebuiltPythonLibraryDescriptionArg args) {
    return new PrebuiltPythonLibrary(
        buildTarget,
        context.getProjectFilesystem(),
        params,
        args.getBinarySrc(),
        args.isExcludeDepsFromMergedLinking());
  }

  @Override
  public boolean producesCacheableSubgraph() {
    return true;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractPrebuiltPythonLibraryDescriptionArg
      extends CommonDescriptionArg, HasDeclaredDeps {
    SourcePath getBinarySrc();

    @Value.Default
    default boolean isExcludeDepsFromMergedLinking() {
      return false;
    }
  }
}
