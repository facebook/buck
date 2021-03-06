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

import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.facebook.buck.core.starlark.testutil.TestStarlarkParser;
import com.facebook.buck.core.starlark.testutil.TestStarlarkThread;
import com.facebook.buck.skylark.function.SkylarkRuleFunctions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import java.util.Objects;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
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
    public void repr(Printer printer) {}

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
                StarlarkList.immutableCopyOf(
                    ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))),
                "bar",
                "bar_value",
                "empty",
                Starlark.NONE));

    String expectedRepr =
        "FakeUserDefinedProviderInfo(foo = [1, 2, 3], bar = \"bar_value\", empty = None)";

    assertEquals(expectedRepr, new Printer().repr(info).toString());
  }

  @Test
  public void getFieldNamesAndValues() {
    UserDefinedProviderInfo info =
        new UserDefinedProviderInfo(
            new FakeUserDefinedProvider(),
            ImmutableMap.of(
                "foo",
                StarlarkList.immutableCopyOf(
                    ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))),
                "bar",
                "bar_value",
                "empty",
                Starlark.NONE));

    assertEquals(ImmutableList.of("foo", "bar", "empty"), info.getFieldNames().asList());
    assertEquals(Starlark.NONE, info.getValue("empty"));
    assertEquals("bar_value", info.getValue("bar"));
    assertNull(info.getValue("bad_field"));
  }

  @Test
  public void worksInSkylark() throws Exception {
    UserDefinedProviderInfo info =
        new UserDefinedProviderInfo(
            new FakeUserDefinedProvider(),
            ImmutableMap.of(
                "foo",
                StarlarkList.immutableCopyOf(
                    ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3))),
                "bar",
                "bar_value",
                "empty",
                Starlark.NONE));

    ImmutableMap<String, Object> map = ImmutableMap.of("info", info);
    assertEquals(info.getValue("foo"), TestStarlarkParser.eval("info.foo", map));
    assertEquals(info.getValue("bar"), TestStarlarkParser.eval("info.bar", map));
    assertEquals(info.getValue("empty"), TestStarlarkParser.eval("info.empty", map));

    thrown.expect(EvalException.class);
    thrown.expectMessage("no field named invalid");
    assertEquals(info.getValue("foo"), TestStarlarkParser.eval("info.invalid", map));
  }

  @Test
  public void isImmutableWorks() throws Exception {

    String buildFile =
        "ui1 = UserInfo(value=\"foo\", immutable=immutable_list, mutable=mutable_list)\n"
            + "ui1.mutable.append(7)\n"
            + "ui2 = UserInfo(value=ui1, immutable=immutable_list, mutable=mutable_list)\n"
            + "ui2.mutable.append(8)\n"
            + "ui2";

    SkylarkRuleFunctions functions = new SkylarkRuleFunctions();
    UserDefinedProvider userInfo =
        functions.provider(
            "",
            StarlarkList.immutableCopyOf(ImmutableList.of("value", "immutable", "mutable")),
            TestStarlarkThread.dummyStarlarkThread());
    userInfo.export(Label.parseAbsolute("//:foo.bzl"), "UserInfo");
    StarlarkList<StarlarkInt> immutableList =
        StarlarkList.immutableCopyOf(
            ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)));

    UserDefinedProviderInfo out1;
    UserDefinedProviderInfo out2;

    try (TestMutableEnv env =
        new TestMutableEnv(
            ImmutableMap.of("immutable_list", immutableList, "UserInfo", userInfo))) {

      StarlarkList<StarlarkInt> mutableList =
          StarlarkList.of(
              env.getEnv().mutability(), StarlarkInt.of(4), StarlarkInt.of(5), StarlarkInt.of(6));
      // TODO: unmodifyable
      env.getModule().setGlobal("mutable_list", mutableList);

      assertFalse(mutableList.isImmutable());

      out2 =
          (UserDefinedProviderInfo)
              TestStarlarkParser.eval(env.getEnv(), env.getModule(), buildFile);

      assertNotNull(out2);
      out1 = (UserDefinedProviderInfo) out2.getValue("value");

      assertNotNull(out1);

      assertFalse(out1.isImmutable());
      assertTrue(Starlark.isImmutable(Objects.requireNonNull(out1.getValue("value"))));
      assertTrue(Starlark.isImmutable(Objects.requireNonNull(out1.getValue("immutable"))));
      assertFalse(Starlark.isImmutable(Objects.requireNonNull(out1.getValue("mutable"))));

      assertFalse(out2.isImmutable());
      assertFalse(Starlark.isImmutable(Objects.requireNonNull(out2.getValue("value"))));
      assertTrue(Starlark.isImmutable(Objects.requireNonNull(out2.getValue("immutable"))));
      assertFalse(Starlark.isImmutable(Objects.requireNonNull(out2.getValue("mutable"))));

      assertEquals(
          ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)),
          ((StarlarkList<?>) out1.getValue("immutable")).getImmutableList());
      assertEquals(
          ImmutableList.of(
              StarlarkInt.of(4),
              StarlarkInt.of(5),
              StarlarkInt.of(6),
              StarlarkInt.of(7),
              StarlarkInt.of(8)),
          ((StarlarkList<?>) out1.getValue("mutable")).getImmutableList());
      assertEquals(
          ImmutableList.of(StarlarkInt.of(1), StarlarkInt.of(2), StarlarkInt.of(3)),
          ((StarlarkList<?>) out2.getValue("immutable")).getImmutableList());
      assertEquals(
          ImmutableList.of(
              StarlarkInt.of(4),
              StarlarkInt.of(5),
              StarlarkInt.of(6),
              StarlarkInt.of(7),
              StarlarkInt.of(8)),
          ((StarlarkList<?>) out2.getValue("mutable")).getImmutableList());
    }

    assertTrue(out1.isImmutable());
    assertTrue(Starlark.isImmutable(Objects.requireNonNull(out1.getValue("value"))));
    assertTrue(Starlark.isImmutable(Objects.requireNonNull(out1.getValue("immutable"))));
    assertTrue(Starlark.isImmutable(Objects.requireNonNull(out1.getValue("mutable"))));

    assertTrue(out2.isImmutable());
    assertTrue(Starlark.isImmutable(Objects.requireNonNull(out2.getValue("value"))));
    assertTrue(Starlark.isImmutable(Objects.requireNonNull(out2.getValue("immutable"))));
    assertTrue(Starlark.isImmutable(Objects.requireNonNull(out2.getValue("mutable"))));
  }
}
