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
package com.facebook.buck.core.rules.providers;

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

  interface Builder {
    Builder put(ProviderInfo<?> info);

    ProviderInfoCollection build();
  }
}
