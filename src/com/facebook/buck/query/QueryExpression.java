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

import com.google.common.collect.ImmutableSet;
import java.util.Collection;

/**
 * Base class for expressions in the Buck query language.
 *
 * <p>All queries return a set of targets that match the query expression.
 *
 * <p>All queries must ensure that sufficient graph edges are created in the QueryEnvironment so
 * that all nodes in the result are correctly ordered according to the type of query. For example,
 * "deps" queries require that all the nodes in the transitive closure of its argument set are
 * correctly ordered w.r.t. each other algebraic set operations such as intersect and union are
 * inherently unordered.
 *
 * <h2>Package overview</h2>
 *
 * <p>This package consists of two basic class hierarchies. The first, {@code QueryExpression}, is
 * the set of different query expressions in the language, and the {@link #eval} method of each
 * defines the semantics. The result of evaluating a query is set of Buck {@code QueryTarget}s (a
 * file or build target). The set may be interpreted as either a set or as nodes of a DAG, depending
 * on the context.
 *
 * @param <NODE_TYPE> Type of the node in the {@link QueryEnvironment} this expression should be
 *     evaluated in the context of.
 */
public abstract class QueryExpression<NODE_TYPE> {

  /** Scan and parse the specified query expression. */
  public static <NODE_TYPE> QueryExpression<NODE_TYPE> parse(
      String query, QueryEnvironment<NODE_TYPE> env) throws QueryException {
    return QueryParser.parse(query, env);
  }

  protected QueryExpression() {}

  /**
   * Evaluates this query in the specified environment, and returns a (possibly-immutable) set of
   * targets.
   *
   * <p>Failures resulting from evaluation of an ill-formed query cause QueryException to be thrown.
   */
  abstract <OUTPUT_TYPE extends QueryTarget> ImmutableSet<OUTPUT_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator, QueryEnvironment<NODE_TYPE> env) throws QueryException;

  /**
   * Collects all target patterns that are referenced anywhere within this query expression and adds
   * them to the given collection, which must be mutable.
   *
   * <p>This is intended to accumulate patterns from multiple expressions for preloading at once.
   */
  public void collectTargetPatterns(Collection<String> literals) {
    traverse(new TargetPatternCollector<NODE_TYPE>(literals));
  }

  /** Accepts and applies the given visitor. */
  public abstract void traverse(Visitor<NODE_TYPE> visitor);

  /** Returns a set of all targets referenced from literals within this query expression. */
  public ImmutableSet<QueryTarget> getTargets(QueryEnvironment<NODE_TYPE> env) {
    QueryTargetCollector<NODE_TYPE> collector = new QueryTargetCollector<NODE_TYPE>(env);
    traverse(collector);
    return collector.getTargets();
  }

  /** Returns this query expression pretty-printed. */
  @Override
  public abstract String toString();

  /**
   * Visits a query expression, and returns whether the traversal should continue downwards or stop
   * and ignore any subexpressions.
   */
  interface Visitor<T> {
    VisitResult visit(QueryExpression<T> exp);
  }

  enum VisitResult {
    CONTINUE,
    SKIP_SUBTREE
  }
}
