/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.macros.MacroFinderAutomaton;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.macros.Macro;
import com.facebook.buck.rules.macros.MacroContainer;
import com.facebook.buck.rules.macros.StringWithMacros;
import com.facebook.buck.util.types.Either;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import java.nio.file.Path;
import java.util.HashSet;

public class StringWithMacrosTypeCoercer implements TypeCoercer<StringWithMacros> {

  private final ImmutableMap<String, Class<? extends Macro>> macros;
  private final ImmutableMap<Class<? extends Macro>, MacroTypeCoercer<? extends Macro>> coercers;

  private StringWithMacrosTypeCoercer(
      ImmutableMap<String, Class<? extends Macro>> macros,
      ImmutableMap<Class<? extends Macro>, MacroTypeCoercer<? extends Macro>> coercers) {
    Preconditions.checkArgument(
        Sets.difference(coercers.keySet(), new HashSet<>(macros.values())).isEmpty());
    this.macros = macros;
    this.coercers = coercers;
  }

  static StringWithMacrosTypeCoercer from(
      ImmutableMap<String, Class<? extends Macro>> macros,
      ImmutableList<MacroTypeCoercer<? extends Macro>> coercers) {
    return new StringWithMacrosTypeCoercer(
        macros, Maps.uniqueIndex(coercers, MacroTypeCoercer::getOutputClass));
  }

  @Override
  public Class<StringWithMacros> getOutputClass() {
    return StringWithMacros.class;
  }

  @Override
  public boolean hasElementClass(Class<?>... types) {
    for (MacroTypeCoercer<? extends Macro> coercer : coercers.values()) {
      if (coercer.hasElementClass(types)) {
        return true;
      }
    }
    return false;
  }

  private <M extends Macro> void traverse(
      CellPathResolver cellRoots, MacroTypeCoercer<M> coercer, Macro macro, Traversal traversal) {
    coercer.traverse(cellRoots, coercer.getOutputClass().cast(macro), traversal);
  }

  @Override
  public void traverse(
      CellPathResolver cellRoots, StringWithMacros stringWithMacros, Traversal traversal) {
    for (MacroContainer macroContainer : stringWithMacros.getMacros()) {
      MacroTypeCoercer<? extends Macro> coercer =
          Preconditions.checkNotNull(coercers.get(macroContainer.getMacro().getClass()));
      traverse(cellRoots, coercer, macroContainer.getMacro(), traversal);
    }
  }

  private StringWithMacros parse(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      String blob)
      throws CoerceFailedException {

    ImmutableList.Builder<Either<String, MacroContainer>> parts = ImmutableList.builder();

    // Iterate over all macros found in the string, expanding each found macro.
    int lastEnd = 0;
    MacroFinderAutomaton matcher = new MacroFinderAutomaton(blob);
    while (matcher.hasNext()) {
      MacroMatchResult matchResult = matcher.next();

      // Add everything from the original string since the last match to this one.
      if (lastEnd < matchResult.getStartIndex()) {
        parts.add(Either.ofLeft(blob.substring(lastEnd, matchResult.getStartIndex())));
      }

      if (matchResult.isEscaped()) {

        // If the macro is escaped, add it as-is.
        parts.add(
            Either.ofLeft(
                blob.substring(matchResult.getStartIndex() + 1, matchResult.getEndIndex())));

      } else {

        MacroContainer.Builder macroContainer = MacroContainer.builder();

        String macroString = blob.substring(matchResult.getStartIndex(), matchResult.getEndIndex());

        // Extract the macro name and hande the `@` prefix.
        String name = matchResult.getMacroType();
        if (name.startsWith("@")) {
          macroContainer.setOutputToFile(true);
          name = name.substring(1);
        }

        // Look up the macro coercer that owns this macro name.
        Class<? extends Macro> clazz = macros.get(name);
        if (clazz == null) {
          throw new CoerceFailedException(
              String.format(
                  "Macro '%s' not found when expanding '%s'",
                  matchResult.getMacroType(), macroString));
        }
        MacroTypeCoercer<? extends Macro> coercer = Preconditions.checkNotNull(coercers.get(clazz));
        ImmutableList<String> args = matchResult.getMacroInput();

        // Delegate to the macro coercers to parse the macro..
        Macro macro;
        try {
          macro = coercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, args);
        } catch (CoerceFailedException e) {
          throw new CoerceFailedException(
              String.format(
                  "The macro '%s' could not be expanded:\n%s", macroString, e.getMessage()),
              e);
        }

        macroContainer.setMacro(macro);

        parts.add(Either.ofRight(macroContainer.build()));
      }

      lastEnd = matchResult.getEndIndex();
    }

    // Append the remaining part of the original string after the last match.
    if (lastEnd < blob.length()) {
      parts.add(Either.ofLeft(blob.substring(lastEnd, blob.length())));
    }

    return StringWithMacros.of(parts.build());
  }

  @Override
  public StringWithMacros coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      Object object)
      throws CoerceFailedException {
    if (!(object instanceof String)) {
      throw CoerceFailedException.simple(object, getOutputClass());
    }
    return parse(cellRoots, filesystem, pathRelativeToProjectRoot, (String) object);
  }
}
