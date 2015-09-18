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

import com.google.common.base.Preconditions;

import java.util.Collection;
import java.util.Set;

/**
 * A literal set of targets. (The syntax of the string "pattern" determines which.)
 *
 * <pre>expr ::= WORD</pre>
 */
public final class TargetLiteral extends QueryExpression {

  private final String pattern;

  public TargetLiteral(String pattern) {
    this.pattern = Preconditions.checkNotNull(pattern);
  }

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env) throws QueryException, InterruptedException {
    return env.getTargetsMatchingPattern(pattern);
  }

  @Override
  public void collectTargetPatterns(Collection<String> literals) {
    literals.add(pattern);
  }

  @Override
  public String toString() {
    // Keep predicate consistent with Lexer.scanWord!
    boolean needsQuoting = Lexer.isReservedWord(pattern) ||
        pattern.isEmpty() ||
        "$-*".indexOf(pattern.charAt(0)) != -1;
    return needsQuoting ? ("\"" + pattern + "\"") : pattern;
  }

  @Override
  public boolean equals(Object other) {
    return (other instanceof TargetLiteral) && pattern.equals(((TargetLiteral) other).pattern);
  }

  @Override
  public int hashCode() {
    return pattern.hashCode();
  }
}
