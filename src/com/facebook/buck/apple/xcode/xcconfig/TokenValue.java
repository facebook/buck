/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.apple.xcode.xcconfig;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.util.Objects;

import javax.annotation.Nullable;

/**
 * A piece of value for an entry in a xcconfig file
 */
public class TokenValue {
  enum Type {
    /** literal strings */
    LITERAL,
    /** reference to another value by its name */
    INTERPOLATION,
  }

  public final Type type;
  @Nullable public final String literalValue;
  @Nullable public final ImmutableList<TokenValue> interpolationValue;

  private TokenValue(
      Type type,
      @Nullable String literalValue,
      @Nullable ImmutableList<TokenValue> interpolationValue) {
    this.type = type;
    this.literalValue = literalValue;
    this.interpolationValue = interpolationValue;
  }

  public static TokenValue literal(String value) {
    return new TokenValue(Type.LITERAL, value, null);
  }

  public static TokenValue interpolation(ImmutableList<TokenValue> value) {
    return new TokenValue(Type.INTERPOLATION, null, value);
  }

  public static TokenValue interpolation(String name) {
    return interpolation(ImmutableList.of(TokenValue.literal(name)));
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TokenValue)) {
      return false;
    }

    TokenValue that = (TokenValue) other;
    return Objects.equals(this.type, that.type) &&
        Objects.equals(this.literalValue, that.literalValue) &&
        Objects.equals(this.interpolationValue, that.interpolationValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, literalValue, interpolationValue);
  }

  @Override
  @Nullable
  public String toString() {
    switch (type) {
      case LITERAL: return literalValue;
      case INTERPOLATION: return "$(" + Joiner.on("").join(interpolationValue) + ")";
    }
    // unreachable
    throw new IllegalStateException("'type' should always be of type 'LITERAL' or 'INTERPOLATION'");
  }

  // Interpolate this token in depth using the given interpretation function
  public TokenValue interpolate(Function<String, Optional<TokenValue>> function) {
    switch (type) {
      case LITERAL:
        return this;

      case INTERPOLATION:
        ImmutableList<TokenValue> newValue =
            interpolateList(function, Preconditions.checkNotNull(interpolationValue));
        if (newValue.size() == 1 && newValue.get(0).type == Type.LITERAL) {
          Optional<TokenValue> interpolated = function.apply(newValue.get(0).literalValue);
          if (interpolated.isPresent()) {
            return interpolated.get();
          }
        }
        return interpolation(newValue);
    }
    // unreachable
    throw new IllegalStateException("'type' should always be of type 'LITERAL' or 'INTERPOLATION'");
  }

  // Interpolate the given list of tokens in depth using the given interpretation function
  // Take a chance to collapse a list made of string literals into a singleton literal
  public static ImmutableList<TokenValue> interpolateList(
      Function<String, Optional<TokenValue>> function,
      ImmutableList<TokenValue> values) {
    ImmutableList.Builder<TokenValue> newValues = ImmutableList.builder();
    @Nullable StringBuilder stringValue = new StringBuilder();

    for (TokenValue token : values) {
      TokenValue newToken = token.interpolate(function);

      newValues.add(newToken);

      if (stringValue != null) {
        if (newToken.type != Type.LITERAL) {
          stringValue = null;
        } else {
          stringValue.append(newToken.literalValue);
        }
      }
    }

    if (stringValue != null) {
      return ImmutableList.of(literal(stringValue.toString()));
    }
    return newValues.build();
  }

}
