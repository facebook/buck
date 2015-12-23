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
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import org.immutables.value.Value;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * A set(word, ..., word) expression, which computes the union of zero or more
 * target patterns separated by whitespace.  This is intended to support the
 * use-case in which a set of labels written to a file by a previous query
 * expression can be modified externally, then used as input to another query,
 * like so:
 *
 * <pre>
 * % buck query 'allpaths(foo, bar)' | grep ... | sed ... | awk ... >file
 * % buck query "kind(qux_library, set($(<file)))"
 * </pre>
 *
 * <p>The grammar currently restricts the operands of set() to being zero or
 * more words (target patterns), with no intervening punctuation.  In principle
 * this could be extended to arbitrary expressions without grammatical
 * ambiguity, but this seems excessively general for now.
 *
 * <pre>expr ::= SET '(' WORD * ')'</pre>
 */
@BuckStyleImmutable
@Value.Immutable
abstract class AbstractSetExpression extends QueryExpression {

  @Value.Parameter
  abstract ImmutableList<TargetLiteral> getWords();

  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, Executor executor)
      throws QueryException, InterruptedException {
    Set<T> result = new LinkedHashSet<>();
    for (TargetLiteral expr : getWords()) {
      result.addAll(expr.eval(env, executor));
    }
    return result;
  }

  @Override
  public void collectTargetPatterns(Collection<String> literals) {
    for (TargetLiteral expr : getWords()) {
      expr.collectTargetPatterns(literals);
    }
  }

  @Override
  public String toString() {
    return "set(" + Joiner.on(' ').join(getWords()) + ")";
  }
}
