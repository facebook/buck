/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.skylark.parser;

import com.google.common.collect.ImmutableMap;
import javax.annotation.Nullable;
import net.starlark.java.eval.LoadedModule;
import net.starlark.java.eval.StarlarkThread;

/** Extension loader for Starlark. */
class BuckLoader implements StarlarkThread.Loader {

  private final ImmutableMap<String, LoadedModule> modules;

  BuckLoader(ImmutableMap<String, LoadedModule> modules) {
    this.modules = modules;
  }

  @Nullable
  @Override
  public LoadedModule load(String module) {
    return modules.get(module);
  }
}
