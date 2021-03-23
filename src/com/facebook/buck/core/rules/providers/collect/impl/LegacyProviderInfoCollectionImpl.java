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

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.collect.ProviderInfoCollection;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import java.util.Optional;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkSemantics;

/** Implementation of {@link ProviderInfoCollection}. */
public class LegacyProviderInfoCollectionImpl extends ProviderInfoCollection {

  private static final LegacyProviderInfoCollectionImpl INSTANCE =
      new LegacyProviderInfoCollectionImpl();

  private LegacyProviderInfoCollectionImpl() {}

  @Override
  public Object getIndex(StarlarkSemantics semantics, Object key) throws EvalException {
    verifyKeyIsProvider(
        key, "Type Target only supports indexing by object constructors, got %s instead");
    return Starlark.NONE;
  }

  @Override
  public boolean containsKey(StarlarkSemantics semantics, Object key) throws EvalException {
    verifyKeyIsProvider(
        key, "Type Target only supports querying by object constructors, got %s instead");
    return false;
  }

  public static ProviderInfoCollection of() {
    return INSTANCE;
  }

  @Override
  public <T extends ProviderInfo<T>> Optional<T> get(Provider<T> provider) {
    return Optional.empty();
  }

  @Override
  public <T extends ProviderInfo<T>> boolean contains(Provider<T> provider) {
    return false;
  }

  @Override
  public DefaultInfo getDefaultInfo() {
    throw new IllegalStateException(
        "Attempting to access DefaultInfo on a legacy rule that does not expose providers");
  }

  private void verifyKeyIsProvider(Object key, String s) throws EvalException {
    if (!(key instanceof Provider)) {
      throw new EvalException(String.format(s, Starlark.type(key)));
    }
  }
}
