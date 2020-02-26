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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.facebook.buck.parser.LabelCache;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.util.Objects;
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
  public void isImmutableWorks() throws InterruptedException, EvalException, LabelSyntaxException {

    String buildFile =
        "ui1 = UserInfo(value=\"foo\", immutable=immutable_list, mutable=mutable_list)\n"
            + "ui1.mutable.append(7)\n"
            + "ui2 = UserInfo(value=ui1, immutable=immutable_list, mutable=mutable_list)\n"
            + "ui2.mutable.append(8)\n"
            + "ui2";

    SkylarkRuleFunctions functions = new SkylarkRuleFunctions(LabelCache.newLabelCache());
    UserDefinedProvider userInfo =
        functions.provider(
            "",
            SkylarkList.createImmutable(ImmutableList.of("value", "immutable", "mutable")),
            Location.BUILTIN);
    userInfo.export(Label.parseAbsolute("//:foo.bzl", ImmutableMap.of()), "UserInfo");
    SkylarkList<Integer> immutableList = SkylarkList.createImmutable(ImmutableList.of(1, 2, 3));

    UserDefinedProviderInfo out1;
    UserDefinedProviderInfo out2;

    try (TestMutableEnv env =
        new TestMutableEnv(
            ImmutableMap.of("immutable_list", immutableList, "UserInfo", userInfo))) {

      SkylarkList.MutableList<Integer> mutableList =
          SkylarkList.MutableList.of(env.getEnv(), 4, 5, 6);
      env.getEnv().updateAndExport("mutable_list", mutableList);

      assertFalse(mutableList.isImmutable());

      out2 = (UserDefinedProviderInfo) BuildFileAST.eval(env.getEnv(), buildFile);

      assertNotNull(out2);
      out1 = (UserDefinedProviderInfo) out2.getValue("value");

      assertNotNull(out1);

      assertFalse(out1.isImmutable());
      assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out1.getValue("value"))));
      assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out1.getValue("immutable"))));
      assertFalse(EvalUtils.isImmutable(Objects.requireNonNull(out1.getValue("mutable"))));

      assertFalse(out2.isImmutable());
      assertFalse(EvalUtils.isImmutable(Objects.requireNonNull(out2.getValue("value"))));
      assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out2.getValue("immutable"))));
      assertFalse(EvalUtils.isImmutable(Objects.requireNonNull(out2.getValue("mutable"))));

      assertEquals(
          ImmutableList.of(1, 2, 3),
          ((SkylarkList<?>) out1.getValue("immutable")).getImmutableList());
      assertEquals(
          ImmutableList.of(4, 5, 6, 7, 8),
          ((SkylarkList<?>) out1.getValue("mutable")).getImmutableList());
      assertEquals(
          ImmutableList.of(1, 2, 3),
          ((SkylarkList<?>) out2.getValue("immutable")).getImmutableList());
      assertEquals(
          ImmutableList.of(4, 5, 6, 7, 8),
          ((SkylarkList<?>) out2.getValue("mutable")).getImmutableList());
    }

    assertTrue(out1.isImmutable());
    assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out1.getValue("value"))));
    assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out1.getValue("immutable"))));
    assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out1.getValue("mutable"))));

    assertTrue(out2.isImmutable());
    assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out2.getValue("value"))));
    assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out2.getValue("immutable"))));
    assertTrue(EvalUtils.isImmutable(Objects.requireNonNull(out2.getValue("mutable"))));
  }
}
