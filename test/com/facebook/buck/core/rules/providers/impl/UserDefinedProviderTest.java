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

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.vfs.PathFragment;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class UserDefinedProviderTest {
  @Rule public ExpectedException thrown = ExpectedException.none();

  Location location =
      Location.fromPathAndStartColumn(
          PathFragment.create("package").getChild("file.bzl"),
          0,
          10,
          new Location.LineAndColumn(5, 6));

  @Test
  public void reprIsReasonable() throws LabelSyntaxException, EvalException {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});
    provider.export(Label.parseAbsolute("//package:file.bzl", ImmutableMap.of()), "FooInfo");
    String expectedRepr = "FooInfo(foo, bar, baz) defined at package/file.bzl:5:6";

    assertEquals(expectedRepr, Printer.repr(provider));
  }

  @Test
  public void nameIsCorrect() throws LabelSyntaxException, EvalException {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});
    provider.export(Label.parseAbsolute("//package:file.bzl", ImmutableMap.of()), "FooInfo");

    assertEquals("FooInfo", provider.toString());
    assertEquals("FooInfo", provider.getName());
  }

  @Test
  public void mutabilityIsCorrect() throws LabelSyntaxException, EvalException {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});

    assertFalse(provider.isImmutable());
    assertFalse(provider.isExported());

    provider.export(Label.parseAbsolute("//package:file.bzl", ImmutableMap.of()), "FooInfo");

    assertTrue(provider.isImmutable());
    assertTrue(provider.isExported());
  }

  @Test
  public void mutabilityOfInfoIsCorrect()
      throws InterruptedException, EvalException, LabelSyntaxException {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});
    provider.export(Label.parseAbsolute("//package:file.bzl", ImmutableMap.of()), "FooInfo");

    try (TestMutableEnv env = new TestMutableEnv()) {
      UserDefinedProviderInfo providerInfo =
          (UserDefinedProviderInfo)
              provider.callWithArgArray(
                  new Object[] {"val_1", "val_2", "val_3"}, null, env.getEnv(), Location.BUILTIN);
      Assert.assertTrue(providerInfo.isImmutable());
    }
  }

  @Test
  public void getNameFailsIfNotExported() {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});

    thrown.expect(NullPointerException.class);
    provider.getName();
  }

  @Test
  public void callFailsIfNotExported() throws InterruptedException, EvalException {
    UserDefinedProvider provider = new UserDefinedProvider(location, new String[] {"foo"});

    try (TestMutableEnv env = new TestMutableEnv()) {
      thrown.expect(NullPointerException.class);
      provider.call(ImmutableList.of(), ImmutableMap.of("foo", "foo_value"), null, env.getEnv());
    }
  }

  @Test
  public void callGetsNoneForValuesNotProvided()
      throws InterruptedException, EvalException, LabelSyntaxException {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});
    provider.export(Label.parseAbsolute("//package:file.bzl", ImmutableMap.of()), "FooInfo");

    try (TestMutableEnv env = new TestMutableEnv()) {
      Object rawInfo =
          provider.call(
              ImmutableList.of(),
              ImmutableMap.of("foo", "foo_value", "baz", "baz_value"),
              null,
              env.getEnv());

      assertTrue(rawInfo instanceof UserDefinedProviderInfo);

      UserDefinedProviderInfo info = (UserDefinedProviderInfo) rawInfo;
      assertEquals("foo_value", info.getValue("foo"));
      assertEquals(Runtime.NONE, info.getValue("bar"));
      assertEquals("baz_value", info.getValue("baz"));
    }
  }

  @Test
  public void callReturnsCorrectUserDefinedProviderInfo()
      throws LabelSyntaxException, InterruptedException, EvalException {
    UserDefinedProvider provider =
        new UserDefinedProvider(location, new String[] {"foo", "bar", "baz"});
    provider.export(Label.parseAbsolute("//package:file.bzl", ImmutableMap.of()), "FooInfo");

    try (TestMutableEnv env = new TestMutableEnv()) {
      Object rawInfo =
          provider.call(
              ImmutableList.of(),
              ImmutableMap.of("foo", "foo_value", "bar", "bar_value", "baz", "baz_value"),
              null,
              env.getEnv());

      assertTrue(rawInfo instanceof UserDefinedProviderInfo);

      UserDefinedProviderInfo info = (UserDefinedProviderInfo) rawInfo;
      assertEquals("foo_value", info.getValue("foo"));
      assertEquals("bar_value", info.getValue("bar"));
      assertEquals("baz_value", info.getValue("baz"));
    }
  }

  @Test
  public void keysAreDifferentForSameNameAndLocation() {
    UserDefinedProvider provider1 = new UserDefinedProvider(location, new String[] {"foo"});
    UserDefinedProvider provider2 = new UserDefinedProvider(location, new String[] {"foo"});

    assertNotEquals(provider1.getKey(), provider2.getKey());
  }
}
