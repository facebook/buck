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

package com.facebook.buck.core.rules.providers;

import com.google.devtools.build.lib.skylarkinterface.SkylarkValue;

/**
 * Declared Provider (a constructor for {@link ProviderInfo}).
 *
 * <p>{@link Provider} serves both as "type identifier" for declared provider instances and as a
 * function that can be called to construct an info of a provider. To the Skylark user, there are
 * "providers" and "provider infos"; the former is a Java instance of this class, and the latter is
 * a Java instance of {@link ProviderInfo}.
 */
public interface Provider<T extends ProviderInfo<T>> extends SkylarkValue {

  /**
   * Returns a serializable representation of this {@link
   * com.google.devtools.build.lib.packages.Provider}.
   */
  Key<T> getKey();

  /**
   * Returns a name of this {@link com.google.devtools.build.lib.packages.Provider} that should be
   * used in error messages.
   */
  @Override
  String toString();

  /**
   * A serializable unique identifier of a {@link Provider}. Providers that create the same type of
   * info should share the same key.
   */
  interface Key<T> {}
}
