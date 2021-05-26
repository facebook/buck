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

package com.facebook.buck.skylark.parser;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Map;
import javax.annotation.Nullable;
import net.starlark.java.eval.LoadedModule;
import net.starlark.java.eval.Module;

/** Implementation of {@link LoadedModule} for Buck. */
class BuckStarlarkLoadedModule implements LoadedModule {
  private final Module module;
  private final Map<String, Object> loadSymbolsSymbols;

  public BuckStarlarkLoadedModule(Module module, Map<String, Object> loadSymbolsSymbols) {
    this.module = module;
    this.loadSymbolsSymbols = loadSymbolsSymbols;
  }

  @Nullable
  @Override
  public Object getGlobal(String name) {
    Object r = module.getGlobal(name);
    if (r == null) {
      // TODO(nga): report error on duplicate?
      r = loadSymbolsSymbols.get(name);
    }
    return r;
  }

  @Override
  public Collection<String> getGlobalNamesForSpelling() {
    ImmutableSet.Builder<String> names = ImmutableSet.builder();
    names.addAll(module.getGlobals().keySet());
    names.addAll(loadSymbolsSymbols.keySet());
    return names.build();
  }

  public ImmutableMap<String, Object> getSymbols() {
    if (loadSymbolsSymbols.isEmpty()) {
      return module.getGlobals();
    }
    ImmutableMap.Builder<String, Object> builder =
        ImmutableMap.builderWithExpectedSize(
            module.getGlobals().size() + loadSymbolsSymbols.size());
    builder.putAll(module.getGlobals());
    builder.putAll(loadSymbolsSymbols);
    return builder.build();
  }
}
