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

package com.facebook.buck.skylark.function;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.devtools.build.lib.syntax.StarlarkThread;
import java.util.HashMap;
import java.util.Map;

/** Store symbols exported by {@code load_symbols}. */
public class LoadSymbolsContext {
  private Map<String, Object> loadedSymbols = new HashMap<>();

  /** Store a symbol to the exported symbols map. */
  public void putSymbol(String key, Object value) {
    if (key.startsWith("_")) {
      throw new HumanReadableException(
          "Tried to load private symbol `%s`. load_symbols() can only be used to load public (non `_`-prefixed) symbols",
          key);
    }
    loadedSymbols.put(key, value);
  }

  public Map<String, Object> getLoadedSymbols() {
    return loadedSymbols;
  }

  public void setup(StarlarkThread env) {
    env.setThreadLocal(LoadSymbolsContext.class, this);
  }
}
