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

package com.facebook.buck.skylark.function;

import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.model.label.LabelSyntaxException;
import com.facebook.buck.core.model.label.LabelValidator;
import com.facebook.buck.core.rules.providers.impl.UserDefinedProvider;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.facebook.buck.core.starlark.compatible.BuckStarlarkModule;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.facebook.buck.rules.param.CommonParamNames;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.util.Map;
import java.util.Set;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.StarlarkFunction;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;

/** Provides APIs for creating build rules. */
public class SkylarkRuleFunctions implements SkylarkRuleFunctionsApi {

  /** The attributes that are applicable to all rules. This will expand over time. */
  // TODO: Once list attributes are added, ensure visibility exists
  public static final ImmutableMap<ParamName, Attribute<?>> IMPLICIT_ATTRIBUTES =
      SkylarkRuleFunctionImplicitAttributes.compute();

  public static final ImmutableMap<ParamName, Attribute<?>> IMPLICIT_TEST_ATTRIBUTES =
      SkylarkRuleFunctionImplicitAttributes.computeTest();

  private static final ImmutableSet<ParamName> USER_VISIBLE_IMPLICIT_ATTRIBUTES =
      ImmutableSet.of(CommonParamNames.NAME, CommonParamNames.LICENSES, CommonParamNames.LABELS);
  /**
   * The hidden attributes from IMPLICIT_ATTRIBUTES that are hidden from user's for user defined
   * rules
   */
  public static final Set<ParamName> HIDDEN_IMPLICIT_ATTRIBUTES =
      Sets.filter(
          IMPLICIT_ATTRIBUTES.keySet(), attr -> !USER_VISIBLE_IMPLICIT_ATTRIBUTES.contains(attr));

  @Override
  public Label label(String labelString, StarlarkThread env) throws EvalException {
    // There is some extra implementation work in the Bazel version. At the moment we do not do
    // cell remapping, so we take a simpler approach of making sure that root-relative labels map
    // to whatever cell we're currently executing within. This has the side effect of making any
    // non-root cell labels become absolute.

    try {
      // Label parentLabel = (Label) env.getGlobals().getLabel();
      Label parentLabel = BuckStarlarkModule.ofInnermostEnclosingStarlarkFunction(env);
      LabelValidator.parseAbsoluteLabel(labelString);
      labelString = parentLabel.getRelativeWithRemapping(labelString).getUnambiguousCanonicalForm();
      return Label.parseAbsolute(labelString, false);
    } catch (LabelValidator.BadLabelException | LabelSyntaxException e) {
      throw new EvalException("Illegal absolute label syntax: " + labelString);
    }
  }

  @Override
  public SkylarkUserDefinedRule rule(
      StarlarkFunction implementation,
      Dict<String, AttributeHolder> attrs,
      boolean inferRunInfo,
      boolean test,
      StarlarkThread env)
      throws EvalException {
    Map<String, AttributeHolder> attributesByString =
        Dict.cast(attrs, String.class, AttributeHolder.class, "attrs keyword of rule()");
    ImmutableMap.Builder<ParamName, AttributeHolder> checkedAttributes =
        ImmutableMap.builderWithExpectedSize(attrs.size());

    for (Map.Entry<String, AttributeHolder> entry : attributesByString.entrySet()) {
      checkedAttributes.put(BuckSkylarkTypes.validateKwargName(entry.getKey()), entry.getValue());
    }

    return SkylarkUserDefinedRule.of(
        env.getCallerLocation(),
        implementation,
        test ? IMPLICIT_TEST_ATTRIBUTES : IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        checkedAttributes.build(),
        inferRunInfo,
        test);
  }

  @Override
  public UserDefinedProvider provider(String doc, Object fields, StarlarkThread thread)
      throws EvalException {
    Iterable<String> fieldNames;
    if (fields instanceof StarlarkList<?>) {
      fieldNames = Sequence.cast(fields, String.class, "fields parameter");
    } else if (fields instanceof Dict) {
      fieldNames = Dict.cast(fields, String.class, String.class, "fields").keySet();
    } else {
      throw new EvalException("fields attribute must be either list or dict.");
    }

    for (String field : fieldNames) {
      BuckSkylarkTypes.validateKwargName(field);
    }
    return new UserDefinedProvider(
        thread.getCallerLocation(), Iterables.toArray(fieldNames, String.class));
  }
}
