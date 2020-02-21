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

package com.facebook.buck.core.rules.providers.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.facebook.buck.parser.LabelCache;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.facebook.buck.skylark.parser.FrozenStructProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import com.google.devtools.build.lib.syntax.SkylarkList;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class UserDefinedProviderInfoTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  private static class FakeUserDefinedProvider implements Provider<UserDefinedProviderInfo> {

    private final Key key;

    private FakeUserDefinedProvider() {
      this.key = new Key();
    }

    @Override
    public Provider.Key<UserDefinedProviderInfo> getKey() {
      return key;
    }

    @Override
    public void repr(SkylarkPrinter printer) {}

    @Override
    public String toString() {
      return "FakeUserDefinedProviderInfo";
    }

    private static class Key implements Provider.Key<UserDefinedProviderInfo> {}
  }

  @Test
  public void reprIsReasonable() {
    UserDefinedProviderInfo info =
        new UserDefinedProviderInfo(
            new FakeUserDefinedProvider(),
            ImmutableMap.of(
                "foo",
                SkylarkList.createImmutable(ImmutableList.of(1, 2, 3)),
                "bar",
                "bar_value",
                "empty",
                Runtime.NONE));

    String expectedRepr =
        "FakeUserDefinedProviderInfo(foo = [1, 2, 3], bar = \"bar_value\", empty = None)";

    assertEquals(expectedRepr, Printer.repr(info));
  }

  @Test
  public void getFieldNamesAndValues() {
    UserDefinedProviderInfo info =
        new UserDefinedProviderInfo(
            new FakeUserDefinedProvider(),
            ImmutableMap.of(
                "foo",
                SkylarkList.createImmutable(ImmutableList.of(1, 2, 3)),
                "bar",
                "bar_value",
                "empty",
                Runtime.NONE));

    assertEquals(ImmutableList.of("foo", "bar", "empty"), info.getFieldNames().asList());
    assertEquals(Runtime.NONE, info.getValue("empty"));
    assertEquals("bar_value", info.getValue("bar"));
    assertNull(info.getValue("bad_field"));
  }

  @Test
  public void worksInSkylark() throws InterruptedException, EvalException {
    UserDefinedProviderInfo info =
        new UserDefinedProviderInfo(
            new FakeUserDefinedProvider(),
            ImmutableMap.of(
                "foo",
                SkylarkList.createImmutable(ImmutableList.of(1, 2, 3)),
                "bar",
                "bar_value",
                "empty",
                Runtime.NONE));

    try (TestMutableEnv env = new TestMutableEnv(ImmutableMap.of("info", info))) {
      assertEquals(info.getValue("foo"), BuildFileAST.eval(env.getEnv(), "info.foo"));
      assertEquals(info.getValue("bar"), BuildFileAST.eval(env.getEnv(), "info.bar"));
      assertEquals(info.getValue("empty"), BuildFileAST.eval(env.getEnv(), "info.empty"));

      thrown.expect(EvalException.class);
      thrown.expectMessage("no field named invalid");
      assertEquals(info.getValue("foo"), BuildFileAST.eval(env.getEnv(), "info.invalid"));
    }
  }

  @Test
  public void nestedProviderInfosWork()
      throws InterruptedException, EvalException, LabelSyntaxException {

    SkylarkRuleFunctions functions = new SkylarkRuleFunctions(LabelCache.newLabelCache());

    try (TestMutableEnv env = new TestMutableEnv(ImmutableMap.of())) {
      SkylarkList.MutableList<Integer> mutableList =
          SkylarkList.MutableList.of(env.getEnv(), 1, 2, 3);
      Object structWithList =
          new FrozenStructProvider()
              .struct(SkylarkDict.of(env.getEnv(), "values", mutableList), Location.BUILTIN);
      env.getEnv().update("struct_with_list", structWithList);
      mutableList.append(4, Location.BUILTIN, env.getEnv());

      UserDefinedProvider info =
          functions.provider(
              "", SkylarkList.createImmutable(ImmutableList.of("field")), Location.BUILTIN);
      info.export(Label.parseAbsolute("//foo:bar.bzl", ImmutableMap.of()), "UserInfo");
      env.getEnv().update("UserInfo", info);

      try {
        BuildFileAST.eval(env.getEnv(), "struct_with_list.values.append(5)");
        fail("Expected a failure to mutate");
      } catch (EvalException e) {
        assertThat(e.getMessage(), Matchers.containsString("trying to mutate a frozen object"));
      }

      UserDefinedProviderInfo out =
          (UserDefinedProviderInfo)
              BuildFileAST.eval(env.getEnv(), "UserInfo(field=struct_with_list)");

      SkylarkList outField = (SkylarkList) ((SkylarkInfo) out.getValue("field")).getValue("values");
      assertNotNull(outField);
      assertTrue(outField.isImmutable());
      assertEquals(ImmutableList.of(1, 2, 3), outField.getImmutableList());
    }
  }
}
