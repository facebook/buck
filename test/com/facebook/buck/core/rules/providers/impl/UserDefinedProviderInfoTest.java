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
import static org.junit.Assert.assertNull;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.skylarkinterface.SkylarkPrinter;
import com.google.devtools.build.lib.syntax.BuildFileAST;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkList;
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
}
