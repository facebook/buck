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

package com.facebook.buck.core.starlark.compatible;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.Argument;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.Environment;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.FuncallExpression;
import com.google.devtools.build.lib.syntax.Identifier;
import com.google.devtools.build.lib.syntax.IntegerLiteral;
import com.google.devtools.build.lib.syntax.Mutability;
import com.google.devtools.build.lib.syntax.ParamDescriptor;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckStarlarkFunctionTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void simpleArgument() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "foo", ImmutableList.of("i"), ImmutableList.of(""), ImmutableSet.of()) {
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
        new BuckStarlarkFunction(
            "manyArgs", ImmutableList.of("a", "b"), ImmutableList.of("", ""), ImmutableSet.of()) {
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
        new BuckStarlarkFunction(
            "skylarkLists", ImmutableList.of("list"), ImmutableList.of("[]"), ImmutableSet.of()) {
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
        new BuckStarlarkFunction(
            "toStr", ImmutableList.of("num"), ImmutableList.of(""), ImmutableSet.of()) {
          public String toStr(Integer num) {
            return num.toString();
          }
        };

    try (Mutability mutability = Mutability.create("test")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
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

  @Test
  public void noDefaultValues() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "toStr", ImmutableList.of("num"), ImmutableList.of(), ImmutableSet.of()) {
          public String toStr(Integer num) {
            return num.toString();
          }
        };

    try (Mutability mutability = Mutability.create("test")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
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

  @Test
  public void withPartialNamedAndDefault() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "myFoo",
            ImmutableList.of("numNoDefault", "numWithDefault"),
            ImmutableList.of("1"),
            ImmutableSet.of()) {
          public String myFoo(Integer mand, Integer numNoDefault, Integer withDefault) {
            return String.valueOf(mand + numNoDefault + withDefault);
          }
        };

    try (Mutability mutability = Mutability.create("test")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .setGlobals(
                  Environment.GlobalFrame.createForBuiltins(
                      ImmutableMap.of(function.getMethodDescriptor().getName(), function)))
              .build();

      FuncallExpression ast =
          new FuncallExpression(
              new Identifier("myFoo"),
              ImmutableList.of(
                  new Argument.Positional(new IntegerLiteral(100)),
                  new Argument.Keyword(new Identifier("numNoDefault"), new IntegerLiteral(10))));

      assertEquals("111", ast.eval(env));

      ast =
          new FuncallExpression(
              new Identifier("myFoo"),
              ImmutableList.of(
                  new Argument.Positional(new IntegerLiteral(100)),
                  new Argument.Keyword(new Identifier("numNoDefault"), new IntegerLiteral(10)),
                  new Argument.Keyword(new Identifier("numWithDefault"), new IntegerLiteral(5))));

      assertEquals("115", ast.eval(env));
    }
  }

  @Test
  @SuppressWarnings("unused")
  public void throwsIfNamedParametersMoreThanMethodParameters() throws Throwable {
    expectedException.expect(IllegalArgumentException.class);

    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "myFoo", ImmutableList.of("correct", "extra"), ImmutableList.of(), ImmutableSet.of()) {
          public String myFoo(Integer correct) {
            return "";
          }
        };
  }

  @Test
  @SuppressWarnings("unused")
  public void throwsIfDefaultParametersMoreThanMethodParameters() throws Throwable {
    expectedException.expect(IllegalArgumentException.class);

    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "myFoo", ImmutableList.of(), ImmutableList.of("correct", "extra"), ImmutableSet.of()) {
          public String myFoo(Integer correct) {
            return "";
          }
        };
  }

  @Test
  public void allowsNoneable() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "withNone",
            ImmutableList.of("non_noneable", "noneable"),
            ImmutableList.of("None"),
            ImmutableSet.of("noneable")) {
          public Object withNone(Object nonNoneable, Object noneable) {
            return SkylarkList.createImmutable(ImmutableList.of(nonNoneable, noneable));
          }
        };

    try (Mutability mutability = Mutability.create("test")) {
      Environment env =
          Environment.builder(mutability)
              .setSemantics(BuckStarlark.BUCK_STARLARK_SEMANTICS)
              .setGlobals(
                  Environment.GlobalFrame.createForBuiltins(
                      ImmutableMap.of(
                          function.getMethodDescriptor().getName(),
                          function,
                          "None",
                          Runtime.NONE)))
              .build();

      Object none = BuildFileAST.eval(env, "withNone(noneable=None, non_noneable=1)[1]");
      Object defaultNone = BuildFileAST.eval(env, "withNone(non_noneable=1)[1]");
      Object nonNull = BuildFileAST.eval(env, "withNone(noneable=2, non_noneable=1)[1]");

      assertEquals(Runtime.NONE, none);
      assertEquals(Runtime.NONE, defaultNone);
      assertEquals(2, nonNull);

      expectedException.expect(EvalException.class);
      expectedException.expectMessage("cannot be None");
      BuildFileAST.eval(env, "withNone(noneable=2, non_noneable=None)[1]");
    }
  }
}
