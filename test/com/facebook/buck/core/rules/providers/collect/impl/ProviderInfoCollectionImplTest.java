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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.artifact.Artifact;
import com.facebook.buck.core.rules.analysis.impl.FakeBuiltInProvider;
import com.facebook.buck.core.rules.analysis.impl.FakeInfo;
import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.rules.providers.lib.ImmutableDefaultInfo;
import com.facebook.buck.core.starlark.compatible.MutableObjectException;
import com.facebook.buck.core.starlark.compatible.TestMutableEnv;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.StarlarkContext;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.Runtime;
import com.google.devtools.build.lib.syntax.SkylarkDict;
import java.util.Optional;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ProviderInfoCollectionImplTest {

  @Rule public ExpectedException expectedException = ExpectedException.none();

  StarlarkContext ctx = new StarlarkContext() {};

  private static final DefaultInfo DEFAULT_INFO =
      new ImmutableDefaultInfo(SkylarkDict.empty(), ImmutableList.of());

  @Test
  public void getIndexThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().build(DEFAULT_INFO);
    providerInfoCollection.getIndex(new Object(), Location.BUILTIN, ctx);
  }

  @Test
  public void containsKeyThrowsWhenKeyNotProvider() throws EvalException {
    expectedException.expect(EvalException.class);
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().build(DEFAULT_INFO);
    providerInfoCollection.containsKey(new Object(), Location.BUILTIN, ctx);
  }

  @Test
  public void getProviderWhenPresentReturnsInfo() throws EvalException {
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().build(DEFAULT_INFO);

    assertTrue(
        providerInfoCollection.containsKey(DEFAULT_INFO.getProvider(), Location.BUILTIN, ctx));
    assertEquals(Optional.of(DEFAULT_INFO), providerInfoCollection.get(DEFAULT_INFO.getProvider()));
    assertSame(
        DEFAULT_INFO,
        providerInfoCollection.getIndex(DEFAULT_INFO.getProvider(), Location.BUILTIN, ctx));
  }

  @Test
  public void getProviderWhenNotPresentReturnsEmpty() throws EvalException {
    Provider<?> provider = new FakeBuiltInProvider("fake");
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().build(DEFAULT_INFO);

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
        ProviderInfoCollectionImpl.builder().put(fakeInfo1).put(fakeInfo2).build(DEFAULT_INFO);
    assertEquals(Optional.of(fakeInfo1), providerInfoCollection.get(builtinProvider1));
    assertEquals(Optional.of(fakeInfo2), providerInfoCollection.get(builtInProvider2));

    assertEquals(
        fakeInfo1, providerInfoCollection.getIndex(builtinProvider1, Location.BUILTIN, ctx));
    assertEquals(
        fakeInfo2, providerInfoCollection.getIndex(builtInProvider2, Location.BUILTIN, ctx));
  }

  @Test
  public void getDefaultInfoCorrectly() {
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().build(DEFAULT_INFO);

    assertEquals(DEFAULT_INFO, providerInfoCollection.getDefaultInfo());
  }

  @Test
  public void containsIsCorrect() {
    Provider<FakeInfo> provider = new FakeBuiltInProvider("fake");
    Provider<FakeInfo> missingProvider = new FakeBuiltInProvider("fake");
    ProviderInfo<?> info = new FakeInfo(provider);
    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().put(info).build(DEFAULT_INFO);

    assertTrue(providerInfoCollection.contains(provider));
    assertTrue(providerInfoCollection.contains(DEFAULT_INFO.getProvider()));
    assertFalse(providerInfoCollection.contains(missingProvider));
  }

  @Test
  public void returnsCorrectSkylarkValues() throws EvalException {
    FakeBuiltInProvider builtinProvider1 = new FakeBuiltInProvider("fake1");
    FakeInfo fakeInfo1 = new FakeInfo(builtinProvider1);

    FakeBuiltInProvider builtinProvider2 = new FakeBuiltInProvider("fake2");

    ProviderInfoCollection providerInfoCollection =
        ProviderInfoCollectionImpl.builder().put(fakeInfo1).build(DEFAULT_INFO);

    assertEquals(
        fakeInfo1, providerInfoCollection.getIndex(builtinProvider1, Location.BUILTIN, ctx));
    assertEquals(
        Runtime.NONE, providerInfoCollection.getIndex(builtinProvider2, Location.BUILTIN, ctx));
  }

  @Test
  public void throwsExceptionIfAddingMutableValue() {
    try (TestMutableEnv env = new TestMutableEnv()) {
      ProviderInfoCollectionImpl.Builder collection = ProviderInfoCollectionImpl.builder();
      SkylarkDict<String, Set<Artifact>> mutableDict = SkylarkDict.of(env.getEnv());

      assertFalse(mutableDict.isImmutable());
      expectedException.expect(MutableObjectException.class);
      collection.put(new ImmutableDefaultInfo(mutableDict, ImmutableList.of()));
    }
  }
}
