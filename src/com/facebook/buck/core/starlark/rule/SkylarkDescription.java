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
package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.description.RuleDescription;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.lib.ImmutableWriteActionArgs;
import com.facebook.buck.core.rules.actions.lib.WriteAction;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.nio.file.Paths;

/**
 * Description for User Defined Rules. This Description runs user-supplied implementation functions
 * in order to generate {@link com.facebook.buck.core.rules.actions.Action}s.
 */
public class SkylarkDescription implements RuleDescription<SkylarkDescriptionArg> {

  @Override
  public boolean producesCacheableSubgraph() {
    return false;
  }

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, SkylarkDescriptionArg args)
      throws RuleAnalysisException, ActionCreationException {
    // TODO: BuildTarget should implement Label

    try (Mutability mutability = Mutability.create("analysing target")) {
      SkylarkRuleContext ctx =
          new SkylarkRuleContext(
              Label.parseAbsolute(target.getFullyQualifiedName(), ImmutableMap.of()));
      Environment env = Environment.builder(mutability).useDefaultSemantics().build();
      args.getRule().getImplementation().call(ImmutableList.of(ctx), ImmutableMap.of(), null, env);

      Artifact outputArtifact = context.actionFactory().declareArtifact(Paths.get("output"));

      context
          .actionFactory()
          .createActionAnalysisData(
              WriteAction.class,
              ImmutableSet.of(),
              ImmutableSet.of(outputArtifact),
              new ImmutableWriteActionArgs("some output", false));

      ImmutableDefaultInfo defaultInfo =
          new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of(outputArtifact));

      // TODO: Verify that we get providers back, validate types, etc, etc
      return ProviderInfoCollectionImpl.builder().put(defaultInfo).build();
    } catch (EvalException e) {
      throw new RuleAnalysisException(e, e.print());
    } catch (InterruptedException e) {
      throw new RuleAnalysisException(e, "Interrupted while analyzing rule");
    } catch (LabelSyntaxException e) {
      throw new RuleAnalysisException(e, "Could not convert BuildTarget to Label");
    }
  }

  @Override
  public Class<SkylarkDescriptionArg> getConstructorArgType() {
    return SkylarkDescriptionArg.class;
  }
}
