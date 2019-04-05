/*
 * Copyright 2017-present Facebook, Inc.
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

public interface QueryEvaluator<ENV_NODE_TYPE> {
  /**
   * Evaluates the result of a query expression in the given environment.
   *
   * @param exp the query expression to be evaluated.
   * @param env the environment used for evaluation of the given query.
   * @return the evaluated target set.
   * @throws QueryException if evaluation fails.
   */
  <OUTPUT_TYPE extends QueryTarget> ImmutableSet<OUTPUT_TYPE> eval(
      QueryExpression<ENV_NODE_TYPE> exp, QueryEnvironment<ENV_NODE_TYPE> env)
      throws QueryException;
}
