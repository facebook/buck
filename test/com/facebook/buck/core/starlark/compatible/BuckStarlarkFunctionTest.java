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

import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.devtools.build.lib.syntax.StarlarkList;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class BuckStarlarkFunctionTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void simpleArgument() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("foo", ImmutableList.of("i"), ImmutableList.of("")) {
          public void foo(int i) {
            i++;
          }
        };

    assertEquals("foo", function.getMethodDescriptor().getName());
    ParamDescriptor param =
        Iterables.getOnlyElement(Arrays.asList(function.getMethodDescriptor().getParameters()));

    assertEquals("i", param.getName());
    assertEquals(ImmutableList.of(Integer.class), param.getAllowedClasses());
  }

  @Test
  public void manyArgs() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("manyArgs", ImmutableList.of("a", "b"), ImmutableList.of("", "")) {
          public String manyArgs(String a, String b) {
            return a + b;
          }
        };

    assertEquals("manyArgs", function.getMethodDescriptor().getName());

    ParamDescriptor[] parameters = function.getMethodDescriptor().getParameters();
    assertEquals("a", parameters[0].getName());
    assertEquals(ImmutableList.of(String.class), parameters[0].getAllowedClasses());

    assertEquals("b", parameters[1].getName());
    assertEquals(ImmutableList.of(String.class), parameters[1].getAllowedClasses());
  }

  @Test
  public void skylarkCollection() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("skylarkLists", ImmutableList.of("list"), ImmutableList.of("[]")) {
          public String skylarkLists(StarlarkList<Integer> list) {
            return list.toString();
          }
        };

    assertEquals("skylarkLists", function.getMethodDescriptor().getName());

    ParamDescriptor param =
        Iterables.getOnlyElement(Arrays.asList(function.getMethodDescriptor().getParameters()));

    assertEquals("list", param.getName());
    assertEquals(ImmutableList.of(StarlarkList.class), param.getAllowedClasses());
  }

  @Test
  public void skylarkCall() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("toStr", ImmutableList.of("num"), ImmutableList.of("")) {
          public String toStr(Integer num) {
            return num.toString();
          }
        };

    assertEquals(
        "1",
        TestStarlarkParser.eval(
            "toStr(num=1)", ImmutableMap.of(function.getMethodDescriptor().getName(), function)));
  }

  @Test
  public void noDefaultValues() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction("toStr", ImmutableList.of("num"), ImmutableList.of()) {
          public String toStr(Integer num) {
            return num.toString();
          }
        };

    assertEquals(
        "1",
        TestStarlarkParser.eval(
            "toStr(num=1)", ImmutableMap.of(function.getMethodDescriptor().getName(), function)));
  }

  @Test
  public void withPartialNamedAndDefault() throws Throwable {
    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "myFoo", ImmutableList.of("numNoDefault", "numWithDefault"), ImmutableList.of("1")) {
          public String myFoo(Integer mand, Integer numNoDefault, Integer withDefault) {
            return String.valueOf(mand + numNoDefault + withDefault);
          }
        };

    assertEquals(
        "111",
        TestStarlarkParser.eval(
            "myFoo(100, numNoDefault=10)",
            ImmutableMap.of(function.getMethodDescriptor().getName(), function)));

    assertEquals(
        "115",
        TestStarlarkParser.eval(
            "myFoo(100, numNoDefault=10, numWithDefault=5)",
            ImmutableMap.of(function.getMethodDescriptor().getName(), function)));
  }

  @Test
  @SuppressWarnings("unused")
  public void throwsIfNamedParametersMoreThanMethodParameters() throws Throwable {
    expectedException.expect(IllegalArgumentException.class);

    BuckStarlarkFunction function =
        new BuckStarlarkFunction(
            "myFoo", ImmutableList.of("correct", "extra"), ImmutableList.of()) {
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
            "myFoo", ImmutableList.of(), ImmutableList.of("correct", "extra")) {
          public String myFoo(Integer correct) {
            return "";
          }
        };
  }
}
