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

import com.google.common.base.Joiner;
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

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TokenValue)) {
      return false;
    }

    TokenValue that = (TokenValue) other;
    return Objects.equals(this.type, that.type)
        && Objects.equals(this.literalValue, that.literalValue)
        && Objects.equals(this.interpolationValue, that.interpolationValue);
  }

  @Override
  public int hashCode() {
    return Objects.hash(type, literalValue, interpolationValue);
  }

  @Override
  public String toString() {
    switch (type) {
      case LITERAL: return literalValue;
      case INTERPOLATION: return "$(" + Joiner.on("").join(interpolationValue) + ")";
    }
    return null;
  }

}
