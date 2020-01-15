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

import com.facebook.buck.core.rules.providers.impl.UserDefinedProvider;
import com.facebook.buck.core.starlark.compatible.BuckSkylarkTypes;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.AttributeHolder;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.cmdline.LabelValidator;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.SkylarkUtils;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/** Provides APIs for creating build rules. */
public class SkylarkRuleFunctions implements SkylarkRuleFunctionsApi {

  private final LoadingCache<String, Label> labelCache;

  /** The attributes that are applicable to all rules. This will expand over time. */
  // TODO: Once list attributes are added, ensure visibility exists
  public static final ImmutableMap<String, Attribute<?>> IMPLICIT_ATTRIBUTES =
      SkylarkRuleFunctionImplicitAttributes.compute();

  public static final ImmutableMap<String, Attribute<?>> IMPLICIT_TEST_ATTRIBUTES =
      SkylarkRuleFunctionImplicitAttributes.computeTest();

  private static final ImmutableSet<String> USER_VISIBLE_IMPLICIT_ATTRIBUTES =
      ImmutableSet.of("name", "licenses", "labels");
  /**
   * The hidden attributes from IMPLICIT_ATTRIBUTES that are hidden from user's for user defined
   * rules
   */
  public static final Set<String> HIDDEN_IMPLICIT_ATTRIBUTES =
      Sets.filter(
          IMPLICIT_ATTRIBUTES.keySet(), attr -> !USER_VISIBLE_IMPLICIT_ATTRIBUTES.contains(attr));

  public SkylarkRuleFunctions(LoadingCache<String, Label> labelCache) {
    this.labelCache = labelCache;
  }

  @Override
  public Label label(String labelString, Location loc, Environment env, StarlarkContext context)
      throws EvalException {
    // There is some extra implementation work in the Bazel version. At the moment we do not do
    // cell remapping, so we take a simpler approach of making sure that root-relative labels map
    // to whatever cell we're currently executing within. This has the side effect of making any
    // non-root cell labels become absolute.
    try {
      Label parentLabel = env.getGlobals().getLabel();
      if (parentLabel != null) {
        LabelValidator.parseAbsoluteLabel(labelString);
        labelString =
            parentLabel
                .getRelativeWithRemapping(labelString, ImmutableMap.of())
                .getUnambiguousCanonicalForm();
      }
      return labelCache.get(labelString);
    } catch (LabelValidator.BadLabelException | LabelSyntaxException | ExecutionException e) {
      throw new EvalException(loc, "Illegal absolute label syntax: " + labelString);
    }
  }

  @Override
  public SkylarkUserDefinedRule rule(
      BaseFunction implementation,
      SkylarkDict<String, AttributeHolder> attrs,
      boolean inferRunInfo,
      boolean test,
      Location loc,
      FuncallExpression ast,
      Environment env)
      throws EvalException {
    SkylarkUtils.checkLoadingOrWorkspacePhase(env, "rule", ast.getLocation());

    Map<String, AttributeHolder> checkedAttributes =
        attrs.getContents(String.class, AttributeHolder.class, "attrs keyword of rule()");

    return SkylarkUserDefinedRule.of(
        loc,
        implementation,
        test ? IMPLICIT_TEST_ATTRIBUTES : IMPLICIT_ATTRIBUTES,
        HIDDEN_IMPLICIT_ATTRIBUTES,
        checkedAttributes,
        inferRunInfo,
        test);
  }

  @Override
  public UserDefinedProvider provider(String doc, Object fields, Location location)
      throws EvalException {
    Iterable<String> fieldNames;
    if (fields instanceof SkylarkList<?>) {
      fieldNames = ((SkylarkList<?>) fields).getContents(String.class, "fields parameter");
    } else if (fields instanceof SkylarkDict) {
      fieldNames =
          ((SkylarkDict<?, ?>) fields)
              .getContents(String.class, String.class, "fields parameter")
              .keySet();
    } else {
      throw new EvalException(location, "fields attribute must be either list or dict.");
    }

    for (String field : fieldNames) {
      BuckSkylarkTypes.validateKwargName(location, field);
    }
    return new UserDefinedProvider(location, Iterables.toArray(fieldNames, String.class));
  }
}
