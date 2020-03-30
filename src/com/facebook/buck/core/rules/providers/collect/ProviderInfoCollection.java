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

package com.facebook.buck.core.rules.providers.collect;

import com.facebook.buck.core.rules.providers.Provider;
import com.facebook.buck.core.rules.providers.ProviderInfo;
import com.facebook.buck.core.rules.providers.lib.DefaultInfo;
import com.facebook.buck.core.starlark.compatible.MutableObjectException;
import com.google.devtools.build.lib.syntax.SkylarkIndexable;
import java.util.Optional;

/**
 * Represents a collection of {@link Provider}s and their corresponding {@link ProviderInfo}.
 *
 * <p>This is a mapping of the {@link Provider} via the {@link Provider.Key} to the corresponding
 * {@link ProviderInfo} information that the {@link Provider} propagates.
 *
 * <p>This is {@link SkylarkIndexable}, so that this can be used to access individual provider
 * information from within skylark extension rule implementations.
 */
public interface ProviderInfoCollection extends SkylarkIndexable {

  /** @return the {@link ProviderInfo} of the specific type given the {@link Provider} */
  <T extends ProviderInfo<T>> Optional<T> get(Provider<T> provider);

  /**
   * @return whether collection contains a {@link ProviderInfo} of the specific type given by the
   *     {@link Provider}
   */
  <T extends ProviderInfo<T>> boolean contains(Provider<T> provider);

  /** @return the {@link DefaultInfo} contained in this collection */
  DefaultInfo getDefaultInfo();

  interface Builder {

    /**
     * Add a new {@link ProviderInfo} to the collection. Multiple {@link ProviderInfo} objects with
     * the same {@link Provider.Key} should not be added.
     *
     * @throws MutableObjectException if {@code info} is still mutable.
     */
    Builder put(ProviderInfo<?> info);

    /**
     * Build the {@link ProviderInfoCollection}. The {@link
     * com.facebook.buck.core.rules.providers.lib.DefaultInfo} must be specified here.
     *
     * @throws IllegalArgumentException if a two or more {@link ProviderInfo}s have the same {@link
     *     Provider.Key}
     * @throws MutableObjectException if {@code info} is still mutable.
     */
    ProviderInfoCollection build(DefaultInfo info);
  }
}
