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

/**
 * An interface to {@link ProviderInfo} to use in skylark methods. This allows us to more easily
 * validate user provided {@link com.google.devtools.build.lib.syntax.SkylarkList} and {@link
 * com.google.devtools.build.lib.syntax.SkylarkDict} objects which have problems with generic
 * subtypes. See {@link com.google.devtools.build.lib.syntax.SkylarkList#getContents(Class, String)}
 * for an example of a problem method
 */
public interface SkylarkProviderInfo {
  /** @return The original provider info */
  ProviderInfo<?> getProviderInfo();
}
