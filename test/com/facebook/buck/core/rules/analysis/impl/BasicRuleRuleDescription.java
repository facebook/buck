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
import com.facebook.buck.core.description.arg.HasDeclaredDeps;
import com.facebook.buck.core.description.arg.HasSrcs;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.ActionExecutionContext;
import com.facebook.buck.core.rules.actions.ActionExecutionResult;
import com.facebook.buck.core.rules.actions.ActionRegistry;
import com.facebook.buck.core.rules.actions.FakeAction;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.util.immutables.RuleArg;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Iterables;
import com.google.common.io.CharStreams;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.BazelLibrary;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class BasicRuleRuleDescription implements RuleDescription<BasicRuleDescriptionArg> {

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, BasicRuleDescriptionArg args)
      throws ActionCreationException {
    ActionRegistry actionRegistry = context.actionRegistry();

    ImmutableSortedSet.Builder<Artifact> allArtifactsBuilder = ImmutableSortedSet.naturalOrder();

    ImmutableSet<Artifact> defaultOutputs =
        getDefaultOutputs(actionRegistry, args.getDefaultOuts());
    allArtifactsBuilder.addAll(defaultOutputs);

    SkylarkDict<String, Set<Artifact>> namedOutputs =
        getNamedOutputs(context.actionRegistry(), args);
    namedOutputs.values().forEach(artifacts -> allArtifactsBuilder.addAll(artifacts));

    FakeAction.FakeActionExecuteLambda actionExecution =
        new FakeAction.FakeActionExecuteLambda() {
          @Override
          public int hashCode() {
            return Objects.hash(target, args);
          }

          @Override
          public boolean equals(Object other) {
            if (other instanceof FakeAction.FakeActionExecuteLambda) {
              return Objects.hash(other) == this.hashCode();
            }
            return false;
          }

          @Override
          public ActionExecutionResult apply(
              ImmutableSortedSet<Artifact> srcs,
              ImmutableSortedSet<Artifact> inputs,
              ImmutableSortedSet<Artifact> outputs,
              ActionExecutionContext ctx) {
            Map<String, Object> data = new HashMap<>();
            data.put("target", target.getShortName());
            data.put("val", args.getVal());

            data.put("srcs", Iterables.transform(srcs, src -> src.asBound().getShortPath()));

            List<Object> deps = new ArrayList<>();
            data.put("dep", deps);
            data.put("outputs", Iterables.transform(outputs, out -> out.asBound().getShortPath()));

            for (Artifact inArtifact : inputs) {
              try (Reader reader =
                  new InputStreamReader(ctx.getArtifactFilesystem().getInputStream(inArtifact))) {
                deps.add(
                    ObjectMappers.createParser(CharStreams.toString(reader))
                        .readValueAs(Map.class));
              } catch (IOException e) {
                return ActionExecutionResult.failure(
                    Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
              }
            }
            for (Artifact output : outputs) {
              try (OutputStream outputStream =
                  ctx.getArtifactFilesystem().getOutputStream(output)) {
                outputStream.write(
                    ObjectMappers.WRITER.writeValueAsString(data).getBytes(Charsets.UTF_8));
              } catch (IOException e) {
                return ActionExecutionResult.failure(
                    Optional.empty(), Optional.empty(), ImmutableList.of(), Optional.of(e));
              }
            }
            return ActionExecutionResult.success(
                Optional.empty(), Optional.empty(), ImmutableList.of());
          }
        };

    ImmutableSortedSet.Builder<Artifact> inputsBuilder = ImmutableSortedSet.naturalOrder();
    for (ProviderInfoCollection providerInfoCollection :
        context.resolveDeps(args.getDeps()).values()) {
      providerInfoCollection
          .get(DefaultInfo.PROVIDER)
          .ifPresent(info -> inputsBuilder.addAll(info.defaultOutputs()));
    }

    new FakeAction(
        context.actionRegistry(),
        context.resolveSrcs(args.getSrcs()),
        inputsBuilder.build(),
        allArtifactsBuilder.build(),
        actionExecution);
    return ProviderInfoCollectionImpl.builder()
        .build(new ImmutableDefaultInfo(namedOutputs, defaultOutputs));
  }

  @Override
  public Class<BasicRuleDescriptionArg> getConstructorArgType() {
    return BasicRuleDescriptionArg.class;
  }

  private ImmutableSet<Artifact> getDefaultOutputs(
      ActionRegistry actionRegistry, Optional<ImmutableSet<String>> defaultOuts) {
    return declareArtifacts(
        actionRegistry, defaultOuts.isPresent() ? defaultOuts.get() : ImmutableSet.of("output"));
  }

  private SkylarkDict<String, Set<Artifact>> getNamedOutputs(
      ActionRegistry actionRegistry, BasicRuleDescriptionArg args) {
    if (!args.getNamedOuts().isPresent()) {
      return SkylarkDict.empty();
    }
    ImmutableMap<String, ImmutableSet<String>> namedOuts = args.getNamedOuts().get();
    SkylarkDict<String, Set<Artifact>> dict;
    try (Mutability mutability = Mutability.create("test")) {
      Environment env =
          Environment.builder(mutability)
              .setGlobals(BazelLibrary.GLOBALS)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .build();
      dict = SkylarkDict.of(env);

      for (Map.Entry<String, ImmutableSet<String>> labelsToNamedOutnames : namedOuts.entrySet()) {
        try {
          dict.put(
              labelsToNamedOutnames.getKey(),
              declareArtifacts(actionRegistry, labelsToNamedOutnames.getValue()),
              Location.BUILTIN,
              mutability);
        } catch (EvalException e) {
          throw new HumanReadableException("Invalid name %s", labelsToNamedOutnames.getKey());
        }
      }
    }
    return dict;
  }

  private ImmutableSet<Artifact> declareArtifacts(
      ActionRegistry actionRegistry, ImmutableSet<String> outNames) {
    return outNames.stream()
        .map(out -> actionRegistry.declareArtifact(Paths.get(out), Location.BUILTIN))
        .collect(ImmutableSet.toImmutableSet());
  }

  @RuleArg
  interface AbstractBasicRuleDescriptionArg extends BuildRuleArg, HasDeclaredDeps, HasSrcs {
    int getVal();

    Optional<ImmutableSet<String>> getDefaultOuts();

    Optional<ImmutableMap<String, ImmutableSet<String>>> getNamedOuts();
  }
}
