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
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.SkylarkProviderInfo;
import com.facebook.buck.core.rules.providers.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.starlark.eventhandler.ConsoleEventHandler;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.EventKind;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.HashMap;
import java.util.List;

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
              context,
              Label.parseAbsolute(target.getFullyQualifiedName(), ImmutableMap.of()),
              args.getRule().getExportedName(),
              args.getCoercedAttrValues());

      Environment env =
          Environment.builder(mutability)
              .useDefaultSemantics()
              .setEventHandler(
                  new ConsoleEventHandler(
                      context.getEventBus(),
                      EventKind.ALL_EVENTS,
                      ImmutableSet.of(),
                      new HumanReadableExceptionAugmentor(ImmutableMap.of())))
              .build();

      BaseFunction implementation = args.getRule().getImplementation();
      Object implResult = implementation.call(ImmutableList.of(ctx), ImmutableMap.of(), null, env);

      List<SkylarkProviderInfo> returnedProviders =
          SkylarkList.castSkylarkListOrNoneToList(implResult, SkylarkProviderInfo.class, null);

      // TODO: Verify that we get providers back, validate types, etc, etc
      return getProviderInfos(returnedProviders, ImmutableSet.of(), ctx, implementation);
    } catch (EvalException e) {
      throw new RuleAnalysisException(e, e.print());
    } catch (InterruptedException e) {
      throw new RuleAnalysisException(e, "Interrupted while analyzing rule");
    } catch (LabelSyntaxException e) {
      throw new RuleAnalysisException(e, "Could not convert BuildTarget to Label");
    }
  }

  private ProviderInfoCollection getProviderInfos(
      List<SkylarkProviderInfo> implResult,
      ImmutableSet<Artifact> declaredOutputs,
      SkylarkRuleContext ctx,
      BaseFunction implementation)
      throws EvalException {

    ProviderInfoCollectionImpl.Builder infos = ProviderInfoCollectionImpl.builder();

    boolean foundDefaultInfo = false;
    for (SkylarkProviderInfo skylarkInfo : implResult) {
      ProviderInfo<?> info = skylarkInfo.getProviderInfo();
      if (DefaultInfo.PROVIDER.equals(info.getProvider())) {
        foundDefaultInfo = true;
      }
      infos.put(info);
    }
    if (!foundDefaultInfo) {
      // TODO: If we have output params set, use those artifacts
      ImmutableSet<Artifact> outputs = declaredOutputs;
      if (outputs.isEmpty()) {
        outputs = ctx.getOutputs();
      }

      infos.put(new ImmutableDefaultInfo(SkylarkDict.empty(), outputs));
    }

    try {
      return infos.build();
    } catch (IllegalArgumentException e) {
      throw new EvalException(
          implementation.getLocation(),
          duplicateProviderInfoErrorMessage(implResult, implementation.getName()));
    }
  }

  private String duplicateProviderInfoErrorMessage(
      List<SkylarkProviderInfo> implResult, String implMethodName) {
    HashMap<Provider.Key<?>, ProviderInfo<?>> providerInfos = new HashMap<>(implResult.size());
    for (SkylarkProviderInfo skylarkInfo : implResult) {
      ProviderInfo<?> info = skylarkInfo.getProviderInfo();
      Provider.Key<?> key = info.getProvider().getKey();
      ProviderInfo<?> existingInfo = providerInfos.get(key);
      if (existingInfo != null) {
        return String.format(
            "%s() returned two or more Info objects of type %s: %s and %s",
            implMethodName, info.getProvider().getKey(), existingInfo, info);
      }
      providerInfos.put(key, info);
    }
    throw new IllegalStateException(
        "This method should only be called when there are duplicate ProviderInfo objects");
  }

  @Override
  public Class<SkylarkDescriptionArg> getConstructorArgType() {
    return SkylarkDescriptionArg.class;
  }
}
