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

import java.util.Set;

/**
 * Interface representing QueryEnvironment's that can accept queries as strings and evaluate them
 */
public interface EvaluatingQueryEnvironment<NODE_TYPE> extends QueryEnvironment<NODE_TYPE> {

  /**
   * Evaluate the specified query expression in this environment.
   *
   * @return the resulting set of targets.
   * @throws QueryException if the evaluation failed.
   */
  Set<NODE_TYPE> evaluateQuery(QueryExpression<NODE_TYPE> expr)
      throws QueryException, InterruptedException;

  default Set<NODE_TYPE> evaluateQuery(String query) throws QueryException, InterruptedException {
    return evaluateQuery(QueryExpression.parse(query, this.getQueryParserEnv()));
  }

  void preloadTargetPatterns(Iterable<String> patterns) throws QueryException, InterruptedException;
}
