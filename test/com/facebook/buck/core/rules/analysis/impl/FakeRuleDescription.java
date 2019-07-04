/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.rules.analysis.impl;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.description.arg.CommonDescriptionArg;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionFailure;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.Optional;
import org.immutables.value.Value;

public class FakeRuleDescription implements RuleDescription<FakeRuleDescriptionArg> {

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, FakeRuleDescriptionArg args)
      throws ActionCreationException {

    Artifact artifact = context.actionRegistry().declareArtifact(Paths.get("output"));

    FakeAction.FakeActionExecuteLambda actionExecution =
        (inputs, outputs, ctx) -> {
          Artifact output = Iterables.getOnlyElement(outputs);
          try {
            try (OutputStream fileout = ctx.getArtifactFilesystem().getOutputStream(output)) {
              fileout.write("testcontent".getBytes(Charsets.UTF_8));
            }
          } catch (IOException e) {
            return ImmutableActionExecutionFailure.of(
                Optional.empty(), Optional.empty(), Optional.of(e));
          }
          return ImmutableActionExecutionSuccess.of(Optional.empty(), Optional.empty());
        };

    new FakeAction(
        context.actionRegistry(), ImmutableSet.of(), ImmutableSet.of(artifact), actionExecution);
    return ProviderInfoCollectionImpl.builder().build();
  }

  @Override
  public Class<FakeRuleDescriptionArg> getConstructorArgType() {
    return FakeRuleDescriptionArg.class;
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractFakeRuleDescriptionArg implements CommonDescriptionArg {}
}
