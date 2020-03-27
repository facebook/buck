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

import com.google.common.collect.ImmutableList;
import java.util.Optional;
import java.util.Set;

/**
 * A "config(x [, configuration])" query expression, which configures the targets in `x` for the
 * given configuration (or their default_target_platform, if a configuration is not provided).
 *
 * <pre>expr ::= CONFIG '(' expr ')'</pre>
 *
 * <pre>expr ::= CONFIG '(' expr ',' WORD ')'</pre>
 */
public class ConfigFunction<NODE_TYPE> implements QueryEnvironment.QueryFunction<NODE_TYPE> {

  private static final ImmutableList<QueryEnvironment.ArgumentType> ARGUMENT_TYPES =
      ImmutableList.of(
          QueryEnvironment.ArgumentType.EXPRESSION, QueryEnvironment.ArgumentType.WORD);

  public ConfigFunction() {}

  @Override
  public String getName() {
    return "config";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public ImmutableList<QueryEnvironment.ArgumentType> getArgumentTypes() {
    return ARGUMENT_TYPES;
  }

  @Override
  public Set<NODE_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator,
      QueryEnvironment<NODE_TYPE> env,
      ImmutableList<QueryEnvironment.Argument<NODE_TYPE>> args)
      throws QueryException {
    // NOTE: In QueryCommand we configure build targets that get passed on the command line. This
    // gets tricky with `compatible_with` as patterns like `//foo/...` might give us nothing if they
    // aren't `compatible_with` their `default_target_platform`. ConfiguredQueryCommand avoids this
    // problem by not configuring targets passed on the command line.
    Set<NODE_TYPE> targets = evaluator.eval(args.get(0).getExpression(), env);
    Optional<String> configurationName =
        args.size() > 1 ? Optional.of(args.get(1).getWord()) : Optional.empty();
    return env.getConfiguredTargets(targets, configurationName);
  }
}
