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

package com.facebook.buck.query;

import com.facebook.buck.core.util.immutables.BuckStyleValue;

/** Information needed to parse (but not evaluate) a query. */
@BuckStyleValue
public abstract class QueryParserEnv<NODE_TYPE> {
  /** Returns the set of query functions implemented by this query environment. */
  public abstract Iterable<QueryEnvironment.QueryFunction<NODE_TYPE>> getFunctions();

  /** Returns an evaluator for target patterns. */
  public abstract QueryEnvironment.TargetEvaluator<NODE_TYPE> getTargetEvaluator();

  /** Constructor. */
  public static <NODE_TYPE> QueryParserEnv<NODE_TYPE> of(
      Iterable<QueryEnvironment.QueryFunction<NODE_TYPE>> functions,
      QueryEnvironment.TargetEvaluator<NODE_TYPE> targetEvaluator) {
    return ImmutableQueryParserEnv.ofImpl(functions, targetEvaluator);
  }
}
