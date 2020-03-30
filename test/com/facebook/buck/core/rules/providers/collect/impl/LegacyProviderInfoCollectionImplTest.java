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

package com.facebook.buck.core.rules.providers.collect.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class LegacyProviderInfoCollectionImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  StarlarkContext ctx = new StarlarkContext() {};

  @Test
  public void getIndexThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection = LegacyProviderInfoCollectionImpl.of();
    providerInfoCollection.getIndex(new Object(), Location.BUILTIN, ctx);
  }

  @Test
  public void containsKeyThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection = LegacyProviderInfoCollectionImpl.of();
    providerInfoCollection.containsKey(new Object(), Location.BUILTIN, ctx);
  }

  @Test
  public void getProviderReturnsNothing() throws EvalException {
    Provider<FakeInfo> provider = new FakeBuiltInProvider("fake");
    ProviderInfoCollection providerInfoCollection = LegacyProviderInfoCollectionImpl.of();

    assertEquals(Optional.empty(), providerInfoCollection.get(provider));
    assertEquals(Runtime.NONE, providerInfoCollection.getIndex(provider, Location.BUILTIN, ctx));
  }

  @Test
  public void containsIsFalse() {
    Provider<FakeInfo> provider = new FakeBuiltInProvider("fake");
    ProviderInfoCollection providerInfoCollection = LegacyProviderInfoCollectionImpl.of();

    assertFalse(providerInfoCollection.contains(provider));
  }

  @Test
  public void getDefaultInfoThrows() throws Exception {
    expectedException.expect(IllegalStateException.class);

    ProviderInfoCollection providerInfoCollection = LegacyProviderInfoCollectionImpl.of();
    providerInfoCollection.getDefaultInfo();
  }
}
