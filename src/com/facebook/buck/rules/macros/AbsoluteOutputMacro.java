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

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/**
 * Macro used to denote the absolute path of an output of a rule. Used when constructing command
 * lines for the rule, e.g. in {@code flags} fields of supporting rules.
 */
@BuckStyleValue
public abstract class AbsoluteOutputMacro implements Macro {

  public static AbsoluteOutputMacro of(String outputName) {
    return ImmutableAbsoluteOutputMacro.of(outputName);
  }

  @Override
  public Class<? extends Macro> getMacroClass() {
    return AbsoluteOutputMacro.class;
  }

  public abstract String getOutputName();
}
