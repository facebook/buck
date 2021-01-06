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

package com.facebook.buck.features.dotnet;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.lib.CopyAction;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.io.filesystem.CopySourceMode;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;

/** implementation for dotnet rules in the rule analysis framework. */
public class PrebuiltDotnetLibraryRuleDescription
    implements RuleDescription<PrebuiltDotnetLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltDotnetLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltDotnetLibraryDescriptionArg.class;
  }

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, PrebuiltDotnetLibraryDescriptionArg args)
      throws ActionCreationException, RuleAnalysisException {

    Artifact assembly = context.resolveSrc(args.getAssembly());
    Artifact output = context.actionRegistry().declareArtifact(Paths.get(assembly.getBasename()));

    new CopyAction(
        context.actionRegistry(), assembly, output.asOutputArtifact(), CopySourceMode.FILE);

    return ProviderInfoCollectionImpl.builder()
        .put(new ImmutableDotnetLibraryProviderInfo(output))
        .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableSet.of(output)));
  }
}
