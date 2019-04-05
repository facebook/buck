/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.query;

import com.google.common.collect.ImmutableSet;
import javax.annotation.Nullable;

/**
 * A literal set of targets. (The syntax of the string "pattern" determines which.)
 *
 * <pre>expr ::= WORD</pre>
 */
public final class TargetLiteral<NODE_TYPE> extends QueryExpression<NODE_TYPE> {
  private final String pattern;

  private TargetLiteral(String pattern) {
    this.pattern = pattern;
  }

  /** @return The value of the {@code pattern} attribute */
  public String getPattern() {
    return pattern;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <OUTPUT_TYPE extends QueryTarget> ImmutableSet<OUTPUT_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator, QueryEnvironment<NODE_TYPE> env) throws QueryException {
    return (ImmutableSet<OUTPUT_TYPE>) env.getTargetsMatchingPattern(getPattern());
  }

  @Override
  public void traverse(QueryExpression.Visitor<NODE_TYPE> visitor) {
    visitor.visit(this);
  }

  @Override
  public String toString() {
    String pattern = getPattern();
    // Keep predicate consistent with Lexer.scanWord!
    boolean needsQuoting =
        Lexer.isReservedWord(pattern)
            || pattern.isEmpty()
            || "$-*".indexOf(pattern.charAt(0)) != -1;
    return needsQuoting ? ("\"" + pattern + "\"") : pattern;
  }

  /**
   * This instance is equal to all instances of {@code TargetLiteral} that have equal attribute
   * values.
   *
   * @return {@code true} if {@code this} is equal to {@code another} instance
   */
  @Override
  @SuppressWarnings("unchecked")
  public boolean equals(@Nullable Object another) {
    if (this == another) {
      return true;
    }
    return another instanceof TargetLiteral && equalTo((TargetLiteral<NODE_TYPE>) another);
  }

  private boolean equalTo(TargetLiteral<NODE_TYPE> another) {
    if (hashCode() != another.hashCode()) {
      return false;
    }
    return pattern.equals(another.pattern);
  }

  @Override
  public int hashCode() {
    return pattern.hashCode();
  }

  /**
   * Construct a new immutable {@code TargetLiteral} instance.
   *
   * @param pattern The value for the {@code pattern} attribute
   * @return An immutable TargetLiteral instance
   */
  public static <T> TargetLiteral<T> of(String pattern) {
    return new TargetLiteral<T>(pattern);
  }
}
