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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.util.immutables.BuckStyleValueWithBuilder;
import org.immutables.value.Value;

/** Packages up a {@link Macro} along some configuration. */
@BuckStyleValueWithBuilder
public abstract class MacroContainer {

  @Value.Parameter
  public abstract Macro getMacro();

  @Value.Parameter
  @Value.Default
  public boolean isOutputToFile() {
    return false;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static MacroContainer of(Macro macro, boolean outputToFile) {
    return ImmutableMacroContainer.of(macro, outputToFile);
  }

  public final MacroContainer withMacro(Macro value) {
    if (getMacro() == value) {
      return this;
    }
    return of(value, isOutputToFile());
  }

  public static class Builder extends ImmutableMacroContainer.Builder {}
}
