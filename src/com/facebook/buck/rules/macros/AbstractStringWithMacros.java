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

package com.facebook.buck.rules.macros;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.immutables.value.Value;

/** A class representing a string containing ordered, embedded, strongly typed macros. */
@Value.Immutable
@BuckStyleTuple
abstract class AbstractStringWithMacros implements TargetTranslatable<StringWithMacros> {

  // The components of the macro string.  Each part is either a plain string or a macro.
  abstract ImmutableList<Either<String, MacroContainer>> getParts();

  /** @return the list of all {@link Macro}s in the macro string. */
  public ImmutableList<MacroContainer> getMacros() {
    return RichStream.from(getParts())
        .filter(Either::isRight)
        .map(Either::getRight)
        .toImmutableList();
  }

  public <T> ImmutableList<T> map(
      Function<? super String, ? extends T> stringMapper,
      Function<? super MacroContainer, ? extends T> macroMapper) {
    return RichStream.from(getParts())
        .map(e -> e.isLeft() ? stringMapper.apply(e.getLeft()) : macroMapper.apply(e.getRight()))
        .toImmutableList();
  }

  /**
   * @return format the macro string into a {@link String}, using {@code mapper} to stringify the
   *     embedded {@link Macro}s.
   */
  public String format(Function<? super MacroContainer, ? extends CharSequence> mapper) {
    return map(s -> s, mapper).stream().collect(Collectors.joining());
  }

  /**
   * @return a new {@link StringWithMacros} with the string components transformed by {@code
   *     mapper}.
   */
  public StringWithMacros mapStrings(Function<String, String> mapper) {
    return StringWithMacros.of(
        RichStream.from(getParts())
            .map(
                e ->
                    e.isRight()
                        ? e
                        : Either.<String, MacroContainer>ofLeft(mapper.apply(e.getLeft())))
            .toImmutableList());
  }

  @Override
  public Optional<StringWithMacros> translateTargets(
      CellPathResolver cellPathResolver,
      BuildTargetPatternParser<BuildTargetPattern> pattern,
      TargetNodeTranslator translator) {
    boolean modified = false;
    ImmutableList.Builder<Either<String, MacroContainer>> parts = ImmutableList.builder();
    for (Either<String, MacroContainer> part : getParts()) {
      if (part.isLeft()) {
        parts.add(part);
      } else {
        Optional<Macro> translated =
            translator.translate(cellPathResolver, pattern, part.getRight().getMacro());
        if (translated.isPresent()) {
          parts.add(Either.ofRight(part.getRight().withMacro(translated.get())));
          modified = true;
        } else {
          parts.add(part);
        }
      }
    }
    return modified ? Optional.of(StringWithMacros.of(parts.build())) : Optional.empty();
  }
}
