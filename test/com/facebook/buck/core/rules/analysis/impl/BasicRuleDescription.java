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
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionFailure;
import com.facebook.buck.core.rules.actions.ImmutableActionExecutionSuccess;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.immutables.value.Value;

public class BasicRuleDescription implements RuleDescription<BasicRuleDescriptionArg> {

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, BasicRuleDescriptionArg args)
      throws ActionCreationException {

    Artifact artifact =
        context.actionRegistry().declareArtifact(Paths.get("output"), Location.BUILTIN);

    FakeAction.FakeActionExecuteLambda actionExecution =
        (inputs, outputs, ctx) -> {
          Artifact output = Iterables.getOnlyElement(outputs);
          try (OutputStream outputStream = ctx.getArtifactFilesystem().getOutputStream(output)) {

            Map<String, Object> data = new HashMap<>();
            data.put("target", target.getShortName());
            data.put("val", args.getVal());

            List<Object> deps = new ArrayList<>();
            data.put("dep", deps);

            for (Artifact inArtifact : inputs) {
              try (Reader reader =
                  new InputStreamReader(ctx.getArtifactFilesystem().getInputStream(inArtifact))) {
                deps.add(
                    ObjectMappers.createParser(CharStreams.toString(reader))
                        .readValueAs(Map.class));
              }
            }
            outputStream.write(
                ObjectMappers.WRITER.writeValueAsString(data).getBytes(Charsets.UTF_8));

          } catch (IOException e) {
            return ImmutableActionExecutionFailure.of(
                Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
          }
          return ImmutableActionExecutionSuccess.of(
              Optional.empty(), Optional.empty(), ImmutableList.of());
        };

    ImmutableSet.Builder<Artifact> inputsBuilder = ImmutableSet.builder();
    for (ProviderInfoCollection providerInfoCollection : context.deps().values()) {
      providerInfoCollection
          .get(DefaultInfo.PROVIDER)
          .ifPresent(info -> inputsBuilder.addAll(info.defaultOutputs()));
    }

    new FakeAction(
        context.actionRegistry(),
        inputsBuilder.build(),
        ImmutableSet.of(artifact),
        actionExecution);
    return ProviderInfoCollectionImpl.builder()
        .build(new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableSet.of(artifact)));
  }

  @Override
  public Class<BasicRuleDescriptionArg> getConstructorArgType() {
    return BasicRuleDescriptionArg.class;
  }

  @BuckStyleImmutable
  @Value.Immutable
  interface AbstractBasicRuleDescriptionArg extends CommonDescriptionArg, HasDeclaredDeps {
    int getVal();
  }
}
