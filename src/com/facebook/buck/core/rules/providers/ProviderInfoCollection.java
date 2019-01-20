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

import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.InfoInterface;
import com.google.devtools.build.lib.packages.Provider;
import com.google.devtools.build.lib.packages.SkylarkInfo;
import com.google.devtools.build.lib.packages.SkylarkProvider;
import com.google.devtools.build.lib.syntax.SkylarkIndexable;
import java.util.Optional;

/**
 * Represents a collection of {@link Provider}s and their corresponding {@link InfoInterface}.
 *
 * <p>This is a mapping of the {@link Provider} via the {@link Provider.Key} to the corresponding
 * {@link InfoInterface} information that the {@link Provider} propagates. Both {@link
 * BuiltinProvider} and {@link SkylarkProvider} are supported.
 *
 * <p>This is {@link SkylarkIndexable}, so that this can be used to access individual provider
 * information from within skylark extension rule implementations.
 */
public interface ProviderInfoCollection extends SkylarkIndexable {

  /**
   * @return the {@link SkylarkInfo} that was created via the given {@link SkylarkProvider} if
   *     exists
   */
  Optional<SkylarkInfo> get(SkylarkProvider provider);

  /** @return the {@link InfoInterface} of the specific type given the {@link BuiltinProvider} */
  <T extends InfoInterface> Optional<T> get(BuiltinProvider<T> provider);

  interface Builder {
    Builder put(InfoInterface info);

    ProviderInfoCollection build();
  }
}
