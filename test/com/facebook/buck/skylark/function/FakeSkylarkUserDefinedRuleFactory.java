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

import static com.facebook.buck.skylark.function.SkylarkRuleFunctions.HIDDEN_IMPLICIT_ATTRIBUTES;
import static com.facebook.buck.skylark.function.SkylarkRuleFunctions.IMPLICIT_ATTRIBUTES;

import com.facebook.buck.core.starlark.rule.SkylarkRuleContext;
import com.facebook.buck.core.starlark.rule.SkylarkUserDefinedRule;
import com.facebook.buck.core.starlark.rule.attr.Attribute;
import com.facebook.buck.core.starlark.rule.attr.impl.StringAttribute;
import com.facebook.buck.rules.param.ParamName;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Dict;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Location;
import com.google.devtools.build.lib.syntax.Starlark;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import com.google.devtools.build.lib.syntax.Tuple;
import java.util.Map;
import java.util.function.Function;

/** Simple container class to make constructing {@link SkylarkUserDefinedRule}s easier in tests */
public class FakeSkylarkUserDefinedRuleFactory {
  private FakeSkylarkUserDefinedRuleFactory() {}

  /**
   * @return a simple rule with a single string argument "baz" that is exported as {@code
   *     //foo:bar.bzl:_impl}
   */
  public static SkylarkUserDefinedRule createSimpleRule()
      throws EvalException, LabelSyntaxException {
    return createSingleArgRule(
        "some_rule", "baz", StringAttribute.of("default", "", false, ImmutableList.of()));
  }

  /** Create a single argument rule with the given argument name and attr to back it */
  public static SkylarkUserDefinedRule createSingleArgRule(
      String exportedName, String attrName, Attribute<?> attr)
      throws EvalException, LabelSyntaxException {

    return createSingleArgRuleWithLabel(exportedName, attrName, attr, "//foo:bar.bzl");
  }

  public static SkylarkUserDefinedRule createSingleArgRuleWithLabel(
      String exportedName, String attrName, Attribute<?> attr, String label)
      throws EvalException, LabelSyntaxException {
    return createRuleFromCallable(exportedName, attrName, attr, label, ctx -> Starlark.NONE);
  }

  public static SkylarkUserDefinedRule createSimpleRuleFromCallable(
      Function<SkylarkRuleContext, Object> callable) throws EvalException, LabelSyntaxException {
    return createRuleFromCallable(
        "some_rule",
        "baz",
        StringAttribute.of("default", "", false, ImmutableList.of()),
        "//foo:bar.bzl",
        callable);
  }

  public static SkylarkUserDefinedRule createRuleFromCallable(
      String exportedName,
      String attrName,
      Attribute<?> attr,
      String label,
      Function<SkylarkRuleContext, Object> callable)
      throws EvalException, LabelSyntaxException {
    return createRuleFromCallable(exportedName, ImmutableMap.of(attrName, attr), label, callable);
  }

  public static SkylarkUserDefinedRule createRuleFromCallable(
      String exportedName,
      ImmutableMap<String, Attribute<?>> attrs,
      String label,
      Function<SkylarkRuleContext, Object> callable)
      throws EvalException, LabelSyntaxException {
    FunctionSignature signature =
        FunctionSignature.create(1, 0, 0, 0, false, false, ImmutableList.of("ctx"));
    BaseFunction implementation =
        new BaseFunction() {
          @Override
          public FunctionSignature getSignature() {
            return signature;
          }

          @Override
          public Object call(StarlarkThread thread, Tuple<Object> args, Dict<String, Object> kwargs)
              throws EvalException, InterruptedException {
            Preconditions.checkArgument(args.size() == 1);
            Preconditions.checkArgument(args.get(0) instanceof SkylarkRuleContext);
            return callable.apply((SkylarkRuleContext) args.get(0));
          }

          @Override
          public String getName() {
            return "unconfigured";
          }
        };
    SkylarkUserDefinedRule ret =
        SkylarkUserDefinedRule.of(
            Location.BUILTIN,
            implementation,
            IMPLICIT_ATTRIBUTES,
            HIDDEN_IMPLICIT_ATTRIBUTES,
            attrs.entrySet().stream()
                .collect(
                    ImmutableMap.toImmutableMap(
                        e -> ParamName.bySnakeCase(e.getKey()), Map.Entry::getValue)),
            false,
            false);
    ret.export(Label.parseAbsolute(label, ImmutableMap.of()), exportedName);

    return ret;
  }
}
