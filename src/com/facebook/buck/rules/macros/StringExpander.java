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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRuleResolver;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.args.StringArg;

/** Expands macros into fixed strings. */
public class StringExpander<M extends Macro> extends SimpleMacroExpander<M> {

  private final Class<M> clazz;
  private final StringArg toReturn;

  public StringExpander(Class<M> clazz, StringArg toReturn) {
    this.clazz = clazz;
    this.toReturn = toReturn;
  }

  @Override
  public Class<M> getInputClass() {
    return clazz;
  }

  @Override
  public Arg expandFrom(
      BuildTarget target, CellPathResolver cellNames, BuildRuleResolver resolver) {
    return toReturn;
  }
}
