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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.core.starlark.rule.attr.impl.ImmutableIntAttribute;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.BaseFunction;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.FunctionSignature;
import com.google.devtools.build.lib.syntax.Runtime;
import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class SkylarkDescriptionArgTest {

  @Rule public ExpectedException expected = ExpectedException.none();

  private SkylarkUserDefinedRule createRule() throws EvalException, LabelSyntaxException {
    FunctionSignature signature = FunctionSignature.of(1, 0, 0, false, false, "ctx");
    BaseFunction implementation =
        new BaseFunction("unconfigured", signature) {
          @Override
          public Object call(Object[] args, @Nullable FuncallExpression ast, Environment env) {
            return Runtime.NONE;
          }
        };
    SkylarkUserDefinedRule ret =
        SkylarkUserDefinedRule.of(
            Location.BUILTIN,
            implementation,
            SkylarkRuleFunctions.IMPLICIT_ATTRIBUTES,
            ImmutableMap.of("baz", new ImmutableIntAttribute(1, "", false, ImmutableList.of())));
    ret.export(Label.parseAbsolute("//foo:bar.bzl", ImmutableMap.of()), "_impl");
    return ret;
  }

  @Test
  public void throwsWhenInvalidFieldIsRequested() throws EvalException, LabelSyntaxException {
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(createRule());

    expected.expect(NullPointerException.class);
    arg.getPostCoercionValue("baz");
  }

  @Test
  public void throwsWhenSettingWithAnInvalidName() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(createRule());

    expected.expect(IllegalStateException.class);
    expected.expectMessage("it was not one of the attributes");
    arg.setPostCoercionValue("not_declared", 1);
  }

  @Test
  public void throwsWhenSettingAfterBuilding() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(createRule());
    arg.build();

    expected.expect(IllegalStateException.class);
    expected.expectMessage("after building an instance");
    arg.setPostCoercionValue("baz", 1);
  }

  @Test
  public void getsValuesThatHaveBeenSet() throws LabelSyntaxException, EvalException {
    SkylarkDescriptionArg arg = new SkylarkDescriptionArg(createRule());

    arg.setPostCoercionValue("baz", 1);
    assertEquals(1, arg.getPostCoercionValue("baz"));
  }
}
