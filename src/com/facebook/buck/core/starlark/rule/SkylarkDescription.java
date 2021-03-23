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

package com.facebook.buck.core.starlark.rule;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.description.RuleDescriptionWithInstanceName;
import com.facebook.buck.core.exceptions.HumanReadableExceptionAugmentor;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.rules.actions.ActionCreationException;
import com.facebook.buck.core.rules.actions.lib.args.CommandLineArgsFactory;
import com.facebook.buck.core.rules.analysis.RuleAnalysisContext;
import com.facebook.buck.core.rules.analysis.RuleAnalysisException;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.SkylarkProviderInfo;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.collect.impl.ProviderInfoCollectionImpl;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableRunInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableTestInfo;
import com.facebook.buck.core.rules.providers.lib.RunInfo;
import com.facebook.buck.core.rules.providers.lib.TestInfo;
import com.facebook.buck.core.starlark.compatible.BuckStarlark;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkPrintHandler;
import com.facebook.buck.core.starlark.eventhandler.ConsoleEventHandler;
import com.facebook.buck.core.starlark.eventhandler.EventKind;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

/**
 * Description for User Defined Rules. This Description runs user-supplied implementation functions
 * in order to generate {@link com.facebook.buck.core.rules.actions.Action}s.
 */
public class SkylarkDescription implements RuleDescriptionWithInstanceName<SkylarkDescriptionArg> {

  @Override
  public boolean producesCacheableSubgraph() {
    return false;
  }

  @Override
  public ProviderInfoCollection ruleImpl(
      RuleAnalysisContext context, BuildTarget target, SkylarkDescriptionArg args)
      throws RuleAnalysisException, ActionCreationException {
    // TODO: BuildTarget should implement Label

    try {
      Object implResult;
      StarlarkCallable implementation;
      SkylarkRuleContext ctx =
          new SkylarkRuleContext(
              context,
              Label.parseAbsolute(target.getFullyQualifiedName()),
              args.getCoercedAttrValues(context));

      try (Mutability mutability = Mutability.create("analysing target")) {
        StarlarkThread env = new StarlarkThread(mutability, BuckStarlark.BUCK_STARLARK_SEMANTICS);
        env.setPrintHandler(
            new BuckStarlarkPrintHandler(
                new ConsoleEventHandler(
                    context.getEventBus(),
                    EventKind.ALL_EVENTS,
                    ImmutableSet.of(),
                    new HumanReadableExceptionAugmentor(ImmutableMap.of()))));

        implementation = args.getImplementation();

        implResult = Starlark.call(env, implementation, Tuple.of(ctx), ImmutableMap.of());
      }

      List<SkylarkProviderInfo> returnedProviders =
          Sequence.noneableCast(implResult, SkylarkProviderInfo.class, null).asList();

      // TODO: Verify that we get providers back, validate types, etc, etc
      return getProviderInfos(returnedProviders, ImmutableSet.of(), ctx, implementation, args);
    } catch (EvalException e) {
      throw new RuleAnalysisException(e, e.toString());
    } catch (InterruptedException e) {
      throw new RuleAnalysisException(e, "Interrupted while analyzing rule");
    } catch (LabelSyntaxException e) {
      throw new RuleAnalysisException(e, "Could not convert BuildTarget to Label");
    }
  }

  @Override
  public String getRuleName(SkylarkDescriptionArg args) {
    return args.getRule().getName();
  }

  private static String getFullName(StarlarkCallable function) {
    return Starlark.classType(function.getClass()) + "." + function.getName();
  }

  private ProviderInfoCollection getProviderInfos(
      List<SkylarkProviderInfo> implResult,
      ImmutableSet<Artifact> declaredOutputs,
      SkylarkRuleContext ctx,
      StarlarkCallable implementation,
      SkylarkDescriptionArg args)
      throws EvalException {

    ProviderInfoCollectionImpl.Builder infos = ProviderInfoCollectionImpl.builder();
    boolean inferRunInfo = args.getRule().shouldInferRunInfo();
    boolean isTest = args.getRule().shouldBeTestRule();

    @Nullable DefaultInfo suppliedDefaultInfo = null;
    @Nullable RunInfo suppliedRunInfo = null;
    @Nullable TestInfo suppliedTestInfo = null;
    for (SkylarkProviderInfo skylarkInfo : implResult) {
      ProviderInfo<?> info = skylarkInfo.getProviderInfo();
      if (DefaultInfo.PROVIDER.equals(info.getProvider()) && suppliedDefaultInfo == null) {
        suppliedDefaultInfo = (DefaultInfo) info;
      } else if (RunInfo.PROVIDER.equals(info.getProvider())) {
        suppliedRunInfo = (RunInfo) info;
      } else if (TestInfo.PROVIDER.equals(info.getProvider())) {
        suppliedTestInfo = (TestInfo) info;
      } else {
        infos.put(info);
      }
    }
    if (suppliedDefaultInfo == null) {
      ImmutableSet<Artifact> outputs = declaredOutputs;
      if (outputs.isEmpty()) {
        outputs = ctx.getOutputs();
      }
      suppliedDefaultInfo =
          new ImmutableDefaultInfo(Dict.empty(), StarlarkList.immutableCopyOf(outputs));
    }
    if (inferRunInfo) {
      if (suppliedRunInfo != null) {
        throw new EvalException(
            implementation.getLocation(),
            String.format(
                "Rule %s for %s specified `infer_run_info`, however a `RunInfo` object was "
                    + "explicitly returned. Either remove RunInfo from the returned values and "
                    + "allow Buck to infer a RunInfo value, or remove `infer_run_info` from the "
                    + "`rule()` declaration",
                getFullName(implementation), ctx.getLabel()));
      }
      if (suppliedDefaultInfo.defaultOutputs().size() != 1) {
        throw new EvalException(
            implementation.getLocation(),
            String.format(
                "Rule %s for %s specified `infer_run_info`, but a RunInfo provider could not be "
                    + "inferred. This provider can only be inferred if the rule returns a single "
                    + "default output in DefaultInfo, rather than %s outputs",
                getFullName(implementation),
                ctx.getLabel(),
                suppliedDefaultInfo.defaultOutputs().size()));
      }
      suppliedRunInfo =
          new ImmutableRunInfo(
              ImmutableMap.of(),
              CommandLineArgsFactory.from(
                  ImmutableList.of(
                      Iterables.getOnlyElement(suppliedDefaultInfo.defaultOutputs()))));
    }
    if (isTest && suppliedTestInfo == null) {
      suppliedTestInfo =
          ImmutableTestInfo.of(
              args.getName(),
              "main",
              args.getLabels(),
              args.getContacts(),
              Optional.empty(),
              false,
              "custom");
    }

    if (suppliedRunInfo != null) {
      infos.put(suppliedRunInfo);
    }

    if (suppliedTestInfo != null) {
      if (isTest) {
        if (suppliedRunInfo == null) {
          throw new EvalException(
              implementation.getLocation(),
              String.format(
                  "Rule %s for %s was marked as a test rule, but did not return a RunInfo object. "
                      + "Either set `infer_run_info` to True to make Buck infer a RunInfo instance, "
                      + "or return a RunInfo instance from your implementation function",
                  getFullName(implementation), ctx.getLabel()));
        }
        infos.put(suppliedTestInfo);
      } else {
        throw new EvalException(
            implementation.getLocation(),
            String.format(
                "Rule %s for %s was not marked as a test rule, but returned a TestInfo provider. "
                    + "Please mark it as a test rule so that the rule() call, and the return value "
                    + "are consistent.",
                getFullName(implementation), ctx.getLabel()));
      }
    }

    try {
      return infos.build(suppliedDefaultInfo);
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
