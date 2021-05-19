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

package net.starlark.java.eval;

import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import javax.annotation.Nullable;

/** A module to be provided to load callback. */
public interface LoadedModule {

  /** Query module. */
  @Nullable
  Object getGlobal(String name);

  /** Names available in the module, used for diagnostics. */
  Collection<String> getGlobalNamesForSpelling();

  /** Simple map-based module. */
  class Simple implements LoadedModule {
    private final ImmutableMap<String, Object> module;

    public Simple(ImmutableMap<String, Object> module) {
      this.module = module;
    }

    @Nullable
    @Override
    public Object getGlobal(String name) {
      return module.get(name);
    }

    @Override
    public Collection<String> getGlobalNamesForSpelling() {
      return module.keySet();
    }
  }
}
