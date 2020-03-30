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

package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.BuildRuleArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.TestProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Optional;

public class FakeRuleRuleDescription implements RuleDescription<FakeRuleDescriptionArg> {

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args)
      throws ActionCreationException {

    Artifact artifact = context.actionRegistry().declareArtifact(Paths.get("output"));

    FakeAction.FakeActionExecuteLambda actionExecution =
        (srcs, inputs, outputs, ctx) -> {
          Artifact output = Iterables.getOnlyElement(outputs);
          try {
            try (OutputStream fileout = ctx.getArtifactFilesystem().getOutputStream(output)) {
              fileout.write("testcontent".getBytes(Charsets.UTF_8));
            }
          } catch (IOException e) {
            return ActionExecutionResult.failure(
                Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
          }
          return ActionExecutionResult.success(
              Optional.empty(), Optional.empty(), ImmutableList.of());
        };

    new FakeAction(
        context.actionRegistry(),
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(),
        ImmutableSortedSet.of(artifact),
        actionExecution);
    return TestProviderInfoCollectionImpl.builder()
        .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(artifact)));
  }

  @Override
  public Class<FakeRuleDescriptionArg> getConstructorArgType() {
    return FakeRuleDescriptionArg.class;
  }

  @RuleArg
  abstract static class AbstractFakeRuleDescriptionArg implements BuildRuleArg {}
}
