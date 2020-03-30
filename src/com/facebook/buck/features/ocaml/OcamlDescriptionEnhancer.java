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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.rules.args.Arg;
import com.facebook.buck.rules.macros.ExecutableMacro;
import com.facebook.buck.rules.macros.ExecutableMacroExpander;
import com.facebook.buck.rules.macros.ExecutableTargetMacro;
import com.facebook.buck.rules.macros.LocationMacroExpander;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.rules.macros.StringWithMacrosConverter;
import com.google.common.collect.ImmutableList;

public class OcamlDescriptionEnhancer {

  private OcamlDescriptionEnhancer() {}

  public static ImmutableList<Arg> toStringWithMacrosArgs(
      BuildTarget target,
      CellPathResolver cellPathResolver,
      ActionGraphBuilder graphBuilder,
      Iterable<StringWithMacros> flags) {
    ImmutableList.Builder<Arg> args = ImmutableList.builder();
    StringWithMacrosConverter macrosConverter =
        StringWithMacrosConverter.of(
            target,
            cellPathResolver.getCellNameResolver(),
            graphBuilder,
            ImmutableList.of(
                LocationMacroExpander.INSTANCE,
                new ExecutableMacroExpander<>(ExecutableMacro.class),
                new ExecutableMacroExpander<>(ExecutableTargetMacro.class)));
    for (StringWithMacros flag : flags) {
      args.add(macrosConverter.convert(flag));
    }
    return args.build();
  }
}
