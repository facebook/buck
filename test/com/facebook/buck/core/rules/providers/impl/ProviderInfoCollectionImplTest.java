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

import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.ProviderInfoCollection;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.EvalException;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProviderInfoCollectionImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  StarlarkContext ctx = new StarlarkContext() {};

  @Test
  public void getIndexThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection = ProviderInfoCollectionImpl.builder().build();
    providerInfoCollection.getIndex(new Object(), Location.BUILTIN, ctx);
  }

  @Test
  public void containsKeyThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection = ProviderInfoCollectionImpl.builder().build();
    providerInfoCollection.containsKey(new Object(), Location.BUILTIN, ctx);
  }

  @Test
  public void getProviderWhenPresentReturnsInfo() throws EvalException {
    Provider<FakeInfo> provider = new FakeBuiltInProvider("fake");
    ProviderInfo<?> info = new FakeInfo(provider);
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().put(info).build();

    assertTrue(providerInfoCollection.containsKey(provider, Location.BUILTIN, ctx));
    assertEquals(Optional.of(info), providerInfoCollection.get(provider));
    assertSame(info, providerInfoCollection.getIndex(provider, Location.BUILTIN, ctx));
  }

  @Test
  public void getProviderWhenNotPresentReturnsEmpty() throws EvalException {
    Provider<?> provider = new FakeBuiltInProvider("fake");
    ProviderInfoCollection providerInfoCollection = ProviderInfoCollectionImpl.builder().build();

    assertFalse(providerInfoCollection.containsKey(provider, Location.BUILTIN, ctx));
    assertEquals(Optional.empty(), providerInfoCollection.get(provider));
  }

  @Test
  public void getCorrectInfoWhenMultipleProvidersPresent() throws EvalException {
    FakeBuiltInProvider builtinProvider1 = new FakeBuiltInProvider("fake1");
    FakeInfo fakeInfo1 = new FakeInfo(builtinProvider1);

    // the fake provider has a new key for every instance for testing purposes
    FakeBuiltInProvider builtInProvider2 = new FakeBuiltInProvider("fake2");
    FakeInfo fakeInfo2 = new FakeInfo(builtInProvider2);

    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().put(fakeInfo1).put(fakeInfo2).build();

    assertEquals(Optional.of(fakeInfo1), providerInfoCollection.get(builtinProvider1));
    assertEquals(Optional.of(fakeInfo2), providerInfoCollection.get(builtInProvider2));

    assertEquals(
        fakeInfo1, providerInfoCollection.getIndex(builtinProvider1, Location.BUILTIN, ctx));
    assertEquals(
        fakeInfo2, providerInfoCollection.getIndex(builtInProvider2, Location.BUILTIN, ctx));
  }
}
