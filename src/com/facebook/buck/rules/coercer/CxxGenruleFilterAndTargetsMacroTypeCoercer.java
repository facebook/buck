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

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.rules.macros.CxxGenruleFilterAndTargetsMacro;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.Queue;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/** Coercer for <code>cxx_genrule</code> flag-based macros. */
public class CxxGenruleFilterAndTargetsMacroTypeCoercer<M extends CxxGenruleFilterAndTargetsMacro>
    implements MacroTypeCoercer<M> {

  private final Optional<TypeCoercer<Pattern>> patternTypeCoercer;
  private final TypeCoercer<ImmutableList<BuildTarget>> buildTargetsTypeCoercer;
  private final Class<M> clazz;
  private final BiFunction<Optional<Pattern>, ImmutableList<BuildTarget>, M> factory;

  public CxxGenruleFilterAndTargetsMacroTypeCoercer(
      Optional<TypeCoercer<Pattern>> patternTypeCoercer,
      TypeCoercer<ImmutableList<BuildTarget>> buildTargetsTypeCoercer,
      Class<M> clazz,
      BiFunction<Optional<Pattern>, ImmutableList<BuildTarget>, M> factory) {
    this.patternTypeCoercer = patternTypeCoercer;
    this.buildTargetsTypeCoercer = buildTargetsTypeCoercer;
    this.clazz = clazz;
    this.factory = factory;
  }

  @Override
  public Class<M> getOutputClass() {
    return clazz;
  }

  @Override
  public boolean hasElementClass(Class<?>[] types) {
    return buildTargetsTypeCoercer.hasElementClass(types);
  }

  @Override
  public void traverse(CellPathResolver cellRoots, M macro, TypeCoercer.Traversal traversal) {
    patternTypeCoercer.ifPresent(
        coercer ->
            macro.getFilter().ifPresent(filter -> coercer.traverse(cellRoots, filter, traversal)));
    buildTargetsTypeCoercer.traverse(cellRoots, macro.getTargets(), traversal);
  }

  @Override
  public M coerce(
      CellPathResolver cellRoots,
      ProjectFilesystem filesystem,
      Path pathRelativeToProjectRoot,
      ImmutableList<String> args)
      throws CoerceFailedException {

    if (args.isEmpty() && patternTypeCoercer.isPresent()) {
      throw new CoerceFailedException(
          String.format("expected at least one argument (found %d)", args.size()));
    }

    Queue<String> mArgs = new ArrayDeque<>(args);

    // Parse filter arg.
    Optional<Pattern> filter = Optional.empty();
    if (patternTypeCoercer.isPresent()) {
      filter =
          Optional.of(
              patternTypeCoercer
                  .get()
                  .coerce(cellRoots, filesystem, pathRelativeToProjectRoot, mArgs.remove()));
    }

    // Parse build target args.
    ImmutableList<BuildTarget> targets =
        buildTargetsTypeCoercer.coerce(cellRoots, filesystem, pathRelativeToProjectRoot, mArgs);

    return factory.apply(filter, targets);
  }
}
