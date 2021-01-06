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

import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.util.stream.RichStream;
import com.facebook.buck.util.string.StringMatcher;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.versions.TargetNodeTranslator;
import com.facebook.buck.versions.TargetTranslatable;
import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.function.Function;

/** A class representing a string containing ordered, embedded, strongly typed macros. */
public abstract class StringWithMacros
    implements TargetTranslatable<StringWithMacros>, StringMatcher {

  private StringWithMacros() {}

  /** The components of the macro string. Each part is either a plain string or a macro. */
  public abstract ImmutableList<Either<String, MacroContainer>> getParts();

  /** String with macros is a sequence of strings and macros. Create it. */
  public static StringWithMacros of(ImmutableList<Either<String, MacroContainer>> parts) {
    return new WithMacros(parts);
  }

  /** Create a string with macros with a single string without macros */
  public static StringWithMacros ofConstantString(String singlePart) {
    if (singlePart.isEmpty()) {
      return Constant.EMPTY;
    } else {
      return new Constant(singlePart);
    }
  }

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
    return String.join("", map(s -> s, mapper));
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
      CellNameResolver cellPathResolver, BaseName targetBaseName, TargetNodeTranslator translator) {
    boolean modified = false;
    ImmutableList.Builder<Either<String, MacroContainer>> parts = ImmutableList.builder();
    for (Either<String, MacroContainer> part : getParts()) {
      if (part.isLeft()) {
        parts.add(part);
      } else {
        Optional<Macro> translated =
            translator.translate(cellPathResolver, targetBaseName, part.getRight().getMacro());
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

  @Override
  public boolean matches(String s) {
    if (hasMacros()) {
      return false;
    }
    String result = "";
    for (Either<String, MacroContainer> part : getParts()) {
      result += part.getLeft();
    }
    return result.equals(s);
  }

  private boolean hasMacros() {
    for (Either<String, MacroContainer> part : getParts()) {
      if (part.isRight()) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof StringWithMacros)) {
      return false;
    }
    // compare parts, so `WithMacros(["s"])` is equal to `Constant("s")`
    return this.getParts().equals(((StringWithMacros) obj).getParts());
  }

  @Override
  public int hashCode() {
    return getParts().hashCode();
  }

  /** String with macros without actual macros. */
  private static class Constant extends StringWithMacros {

    private static final StringWithMacros EMPTY = new Constant("");

    private final String constant;

    private Constant(String constant) {
      this.constant = constant;
    }

    @Override
    public ImmutableList<Either<String, MacroContainer>> getParts() {
      if (constant.isEmpty()) {
        return ImmutableList.of();
      } else {
        return ImmutableList.of(Either.ofLeft(constant));
      }
    }

    // Following functions would work fine with parent class implementations,
    // but specialize them for performance.

    @Override
    public Optional<StringWithMacros> translateTargets(
        CellNameResolver cellPathResolver,
        BaseName targetBaseName,
        TargetNodeTranslator translator) {
      return Optional.empty();
    }

    @Override
    public StringWithMacros mapStrings(Function<String, String> mapper) {
      return ofConstantString(mapper.apply(constant));
    }

    @Override
    public ImmutableList<MacroContainer> getMacros() {
      return ImmutableList.of();
    }

    @Override
    public <T> ImmutableList<T> map(
        Function<? super String, ? extends T> stringMapper,
        Function<? super MacroContainer, ? extends T> macroMapper) {
      return ImmutableList.of(stringMapper.apply(constant));
    }
  }

  /** Default implementation */
  private static class WithMacros extends StringWithMacros {
    private final ImmutableList<Either<String, MacroContainer>> parts;

    public WithMacros(ImmutableList<Either<String, MacroContainer>> parts) {
      this.parts = parts;
    }

    @Override
    public ImmutableList<Either<String, MacroContainer>> getParts() {
      return parts;
    }
  }
}
