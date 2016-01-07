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

package com.facebook.buck.query;

import com.facebook.buck.util.immutables.BuckStyleImmutable;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.Executor;

@BuckStyleImmutable
@Value.Immutable
abstract class AbstractTargetLiteral extends QueryExpression {

  @Value.Parameter
  abstract String getPattern();

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, Executor executor)
      throws QueryException, InterruptedException {
    return env.getTargetsMatchingPattern(getPattern(), executor);
  }

  @Override
  public void collectTargetPatterns(Collection<String> literals) {
    literals.add(getPattern());
  }

  @Override
  public String toString() {
    // Keep predicate consistent with Lexer.scanWord!
    boolean needsQuoting = Lexer.isReservedWord(getPattern()) ||
        getPattern().isEmpty() ||
        "$-*".indexOf(getPattern().charAt(0)) != -1;
    return needsQuoting ? ("\"" + getPattern() + "\"") : getPattern();
  }
}
