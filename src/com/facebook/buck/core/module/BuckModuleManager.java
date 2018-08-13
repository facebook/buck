/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.core.module;

import com.google.common.collect.ImmutableSortedSet;

/** Provides access to module information. */
public interface BuckModuleManager {

  boolean isClassInModule(Class<?> cls);

  String getModuleHash(Class<?> cls);

  /** @return the hash of module's content (which includes code and resources.) */
  String getModuleHash(String moduleId);

  /** @return IDs of all modules known to this instance. */
  ImmutableSortedSet<String> getModuleIds();

  /** @return IDs of all modules the provided module depends on. */
  ImmutableSortedSet<String> getModuleDependencies(String moduleId);
}
