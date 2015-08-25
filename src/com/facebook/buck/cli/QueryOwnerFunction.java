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
package com.facebook.buck.cli;

import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.Argument;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.ArgumentType;
import com.google.devtools.build.lib.query2.engine.QueryEnvironment.QueryFunction;
import com.google.devtools.build.lib.query2.engine.QueryException;
import com.google.devtools.build.lib.query2.engine.QueryExpression;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * A "owner" query expression, which computes the rules that own the given files.
 *
 * <pre>expr ::= OWNER '(' WORD ')'</pre>
 */
public class QueryOwnerFunction implements QueryFunction {

  public QueryOwnerFunction() {
  }

  @Override
  public String getName() {
    return "owner";
  }

  @Override
  public int getMandatoryArguments() {
    return 1;
  }

  @Override
  public List<ArgumentType> getArgumentTypes() {
    return Lists.newArrayList(ArgumentType.WORD);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> Set<T> eval(QueryEnvironment<T> env, QueryExpression expression, List<Argument> args)
      throws QueryException, InterruptedException {
    // Casts are made in order to keep Bazel's structure for evaluating queries unchanged.
    if (!(env instanceof BuckQueryEnvironment)) {
      throw new QueryException("The environment should be an instance of BuckQueryEnvironment");
    }
    try {
      ImmutableList<String> files = ImmutableList.of(args.get(0).getWord());
      BuckQueryEnvironment buckEnv = (BuckQueryEnvironment) env;
      AuditOwnerCommand.OwnersReport report = AuditOwnerCommand.buildOwnersReport(
          buckEnv.getParams(),
          buckEnv.getParserConfig(),
          buckEnv.getBuildFileTree(),
          files,
          /* guessForDeletedEnabled */ false);

      return (Set<T>) Sets.newHashSet(
          Collections2.transform(
              report.owners.keySet(),
              new Function<TargetNode<?>, BuildTarget>() {
                @Override
                public BuildTarget apply(TargetNode<?> input) {
                  return Preconditions.checkNotNull(input.getBuildTarget());
                }
              }));
    } catch (BuildFileParseException | BuildTargetException | IOException e) {
      throw new QueryException("Could not parse build targets.\n" + e.getMessage());
    }
  }

}
