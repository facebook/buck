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

import com.google.common.collect.ImmutableList;
import java.util.Collection;
import javax.annotation.Nullable;
import net.starlark.java.eval.LoadedModule;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.StarlarkValue;

/**
 * Special module, which provides any dummy symbol. This is meant to be a module which is real
 * module in Buck v2, but dummy in Buck v1.
 */
class BuckV2OnlyModule implements LoadedModule {

  private static final String SUFFIX = "?v2_only";

  static boolean isV2OnlyImport(String name) {
    return name.endsWith(SUFFIX);
  }

  static final BuckV2OnlyModule INSTANCE = new BuckV2OnlyModule();

  private BuckV2OnlyModule() {}

  @Nullable
  @Override
  public Object getGlobal(String name) {
    return new BuckV2OnlyValue(name);
  }

  @Override
  public Collection<String> getGlobalNamesForSpelling() {
    // This code is never called because all symbols are resolved.
    return ImmutableList.of();
  }

  private static class BuckV2OnlyValue extends StarlarkValue {
    private final String symbol;

    private BuckV2OnlyValue(String symbol) {
      this.symbol = symbol;
    }

    @Override
    public void repr(Printer printer) {
      printer.append("<v2 only object ").append(symbol).append(">");
    }
  }
}
