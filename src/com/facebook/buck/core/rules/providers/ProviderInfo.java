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
 * The information, which is a struct-like object, passed between rules during rule analysis. This
 * object is created via {@link Provider}.
 */
public interface ProviderInfo<U extends ProviderInfo<U>> extends SkylarkProviderInfo, SkylarkValue {

  /** @return the {@link Provider} instance that constructs instances of this info. */
  Provider<U> getProvider();
}
