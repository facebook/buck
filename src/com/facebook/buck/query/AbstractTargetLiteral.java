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

// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.facebook.buck.query;

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.collect.ImmutableSet;
import java.util.Objects;
import org.immutables.value.Value;

/**
 * A literal set of targets. (The syntax of the string "pattern" determines which.)
 *
 * <pre>expr ::= WORD</pre>
 */
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractTargetLiteral extends QueryExpression {
  abstract String getPattern();

  @Value.Check
  protected void check() {
    Objects.requireNonNull(getPattern());
  }

  @Override
  ImmutableSet<QueryTarget> eval(QueryEvaluator evaluator, QueryEnvironment env)
      throws QueryException {
    return env.getTargetsMatchingPattern(getPattern());
  }

  @Override
  public void traverse(QueryExpression.Visitor visitor) {
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
}
