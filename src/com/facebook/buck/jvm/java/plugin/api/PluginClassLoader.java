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

package com.facebook.buck.jvm.java.plugin.api;

import com.facebook.buck.util.liteinfersupport.Nullable;

/** Utility interface for loading classes that live in Buck's Java compiler plugin. */
public interface PluginClassLoader {
  @Nullable
  <T> Class<? extends T> loadClass(String name, Class<T> superclass);
}
