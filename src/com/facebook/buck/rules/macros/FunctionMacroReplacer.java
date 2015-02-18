/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import com.google.common.base.Function;

/**
 * A @{link MacroReplacer} wrapping a @{link Function}.
 */
public class FunctionMacroReplacer implements MacroReplacer {

  private final Function<String, String> function;

  public FunctionMacroReplacer(Function<String, String> function) {
    this.function = function;
  }

  @Override
  public String replace(String input) throws MacroException {
    return function.apply(input);
  }

}
