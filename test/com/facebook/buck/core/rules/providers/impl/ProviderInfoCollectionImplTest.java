/*
 * Copyright 2018-present Facebook, Inc.
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
package com.facebook.buck.core.rules.providers.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.packages.SkylarkProvider.SkylarkKey;
import com.google.devtools.build.lib.syntax.EvalException;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProviderInfoCollectionImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  @Test
  public void getIndexThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection = ProviderInfoCollectionImpl.builder().build();
    providerInfoCollection.getIndex(new Object(), Location.BUILTIN);
  }

  @Test
  public void containsKeyThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection = ProviderInfoCollectionImpl.builder().build();
    providerInfoCollection.containsKey(new Object(), Location.BUILTIN);
  }

  @Test
  public void getSkylarkProviderWhenPresentReturnsInfo() throws EvalException {
    SkylarkProvider provider = createSkylarkProvider("//my:provider");
    SkylarkInfo info = SkylarkInfo.createSchemaless(provider, ImmutableMap.of(), null);
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().put(info).build();

    assertTrue(providerInfoCollection.containsKey(provider, Location.BUILTIN));
    assertEquals(Optional.of(info), providerInfoCollection.get(provider));
    assertSame(info, providerInfoCollection.getIndex(provider, Location.BUILTIN));
  }

  @Test
  public void getBuildInProviderWhenPresentReturnsInfo() throws EvalException {
    BuiltinProvider<MyTestInfo> provider =
        new BuiltinProvider<MyTestInfo>("//my:provider", MyTestInfo.class) {};

    MyTestInfo info = new MyTestInfo(provider);

    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().put(info).build();

    assertTrue(providerInfoCollection.containsKey(provider, Location.BUILTIN));
    assertEquals(Optional.of(info), providerInfoCollection.get(provider));
    assertSame(info, providerInfoCollection.getIndex(provider, Location.BUILTIN));
  }

  @Test
  public void getProviderWhenNotPresentReturnsEmpty() throws EvalException {
    SkylarkProvider skylarkProvider = createSkylarkProvider("//my:provider");
    BuiltinProvider<MyTestInfo> builtinProvider =
        new BuiltinProvider<MyTestInfo>("myprovider", MyTestInfo.class) {};
    ProviderInfoCollection providerInfoCollection = ProviderInfoCollectionImpl.builder().build();

    assertFalse(providerInfoCollection.containsKey(skylarkProvider, Location.BUILTIN));
    assertFalse(providerInfoCollection.containsKey(builtinProvider, Location.BUILTIN));
    assertEquals(Optional.empty(), providerInfoCollection.get(skylarkProvider));
    assertEquals(Optional.empty(), providerInfoCollection.get(builtinProvider));
    assertEquals(null, providerInfoCollection.getIndex(skylarkProvider, Location.BUILTIN));
    assertEquals(null, providerInfoCollection.getIndex(builtinProvider, Location.BUILTIN));
  }

  @Test
  public void getCorrectInfoWhenMultipleProvidersPresent() throws EvalException {
    SkylarkProvider skylarkProvider1 = createSkylarkProvider("//myskylarkprovider:1");
    SkylarkInfo skylarkInfo1 =
        SkylarkInfo.createSchemaless(skylarkProvider1, ImmutableMap.of(), null);

    SkylarkProvider skylarkProvider2 = createSkylarkProvider("//myskylarkprovider:2");
    SkylarkInfo skylarkInfo2 =
        SkylarkInfo.createSchemaless(skylarkProvider2, ImmutableMap.of(), null);

    BuiltinProvider<MyTestInfo> builtinProvider1 =
        new BuiltinProvider<MyTestInfo>("myprovider", MyTestInfo.class) {};
    MyTestInfo builtinInfo1 = new MyTestInfo(builtinProvider1);

    BuiltinProvider<MyTestInfo> builtinProvider2 =
        new BuiltinProvider<MyTestInfo>("myprovider", MyTestInfo.class) {};
    MyTestInfo builtinInfo2 = new MyTestInfo(builtinProvider2);

    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder()
            .put(skylarkInfo1)
            .put(skylarkInfo2)
            .put(builtinInfo1)
            .put(builtinInfo2)
            .build();

    assertEquals(Optional.of(skylarkInfo1), providerInfoCollection.get(skylarkProvider1));
    assertEquals(Optional.of(skylarkInfo2), providerInfoCollection.get(skylarkProvider2));
    assertEquals(Optional.of(builtinInfo1), providerInfoCollection.get(builtinProvider1));
    assertEquals(Optional.of(builtinInfo2), providerInfoCollection.get(builtinProvider2));

    assertSame(skylarkInfo1, providerInfoCollection.getIndex(skylarkProvider1, Location.BUILTIN));
    assertSame(skylarkInfo2, providerInfoCollection.getIndex(skylarkProvider2, Location.BUILTIN));
    assertSame(builtinInfo1, providerInfoCollection.getIndex(builtinProvider1, Location.BUILTIN));
    assertSame(builtinInfo2, providerInfoCollection.getIndex(builtinProvider2, Location.BUILTIN));
  }

  private SkylarkProvider createSkylarkProvider(String keyname) {
    return SkylarkProvider.createExportedSchemaless(
        new SkylarkKey(Label.parseAbsoluteUnchecked(keyname + "label"), keyname), Location.BUILTIN);
  }

  public static class MyTestInfo extends NativeInfo {
    public MyTestInfo(Provider provider) {
      super(provider);
    }
  }
}
