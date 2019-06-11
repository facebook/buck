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
package com.facebook.buck.core.starlark.compatible;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.Argument;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Identifier;
import com.google.devtools.build.lib.syntax.IntegerLiteral;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParamDescriptor;
import com.google.devtools.build.lib.syntax.SkylarkList;
import org.junit.Test;

public class BuckStarlarkFunctionTest {

  @Test
  public void simpleArgument() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("foo", ImmutableList.of("i")) {
          public void foo(int i) {
            i++;
          }
        };

    assertEquals("foo", function.getMethodDescriptor().getName());
    ParamDescriptor param =
        Iterables.getOnlyElement(function.getMethodDescriptor().getParameters());

    assertEquals("i", param.getName());
    assertEquals(Integer.class, param.getType());
  }

  @Test
  public void manyArgs() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("manyArgs", ImmutableList.of("a", "b")) {
          public String manyArgs(String a, String b) {
            return a + b;
          }
        };

    assertEquals("manyArgs", function.getMethodDescriptor().getName());

    ImmutableList<ParamDescriptor> parameters = function.getMethodDescriptor().getParameters();
    assertEquals("a", parameters.get(0).getName());
    assertEquals(String.class, parameters.get(0).getType());

    assertEquals("b", parameters.get(1).getName());
    assertEquals(String.class, parameters.get(1).getType());
  }

  @Test
  public void skylarkCollection() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("skylarkLists", ImmutableList.of("list")) {
          public String skylarkLists(SkylarkList<Integer> list) {
            return list.toString();
          }
        };

    assertEquals("skylarkLists", function.getMethodDescriptor().getName());

    ParamDescriptor param =
        Iterables.getOnlyElement(function.getMethodDescriptor().getParameters());

    assertEquals("list", param.getName());
    assertEquals(SkylarkList.class, param.getType());
  }

  @Test
  public void skylarkCall() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("toStr", ImmutableList.of("num")) {
          public String toStr(Integer num) {
            return num.toString();
          }
        };

    Mutability mutability = Mutability.create("test");
    Environment env =
        Environment.builder(mutability)
            .useDefaultSemantics()
            .setGlobals(
                Environment.GlobalFrame.createForBuiltins(
                    ImmutableMap.of(function.getMethodDescriptor().getName(), function)))
            .build();

    FuncallExpression ast =
        new FuncallExpression(
            new Identifier("toStr"),
            ImmutableList.of(new Argument.Keyword(new Identifier("num"), new IntegerLiteral(1))));

    assertEquals("1", ast.eval(env));
  }
}
