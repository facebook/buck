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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.TargetEvaluator;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class QueryParserTest {

  private static final ImmutableList<QueryEnvironment.QueryFunction<ConfiguredQueryBuildTarget>>
      QUERY_FUNCTIONS =
          ImmutableList.of(new DepsFunction<>(), new RdepsFunction<>(), new TestsOfFunction<>());

  private QueryParserEnv<ConfiguredQueryBuildTarget> queryEnvironment;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void makeEnvironment() {
    QueryEnvironment.TargetEvaluator<ConfiguredQueryBuildTarget> targetEvaluator =
        new TestTargetEvaluator();
    queryEnvironment = QueryParserEnv.of(QUERY_FUNCTIONS, targetEvaluator);
  }

  @Test
  public void testDeps() throws Exception {
    ImmutableList<Argument<ConfiguredQueryBuildTarget>> args =
        ImmutableList.of(Argument.of(TargetLiteral.of("//foo:bar")));
    QueryExpression<ConfiguredQueryBuildTarget> expected =
        new FunctionExpression(new DepsFunction(), args);

    String query = "deps('//foo:bar')";
    QueryExpression result = QueryParser.parse(query, queryEnvironment);
    assertThat(result, is(equalTo(expected)));
  }

  @Test
  public void testTestsOfDepsSet() throws Exception {
    ImmutableList<TargetLiteral<ConfiguredQueryBuildTarget>> args =
        ImmutableList.of(TargetLiteral.of("//foo:bar"), TargetLiteral.of("//other:lib"));
    QueryExpression<ConfiguredQueryBuildTarget> depsExpr =
        new FunctionExpression(
            new DepsFunction(), ImmutableList.of(Argument.of(SetExpression.of(args))));
    QueryExpression<ConfiguredQueryBuildTarget> testsofExpr =
        new FunctionExpression<>(new TestsOfFunction(), ImmutableList.of(Argument.of(depsExpr)));

    String query = "testsof(deps(set('//foo:bar' //other:lib)))";
    QueryExpression<ConfiguredQueryBuildTarget> result = QueryParser.parse(query, queryEnvironment);
    assertThat(result, is(equalTo(testsofExpr)));
  }

  @Test
  public void shouldThrowExceptionWhenUnexpetedComma() throws QueryException {
    String query = "testsof(deps(set('//foo:bar', //other:lib)))";
    thrown.expect(QueryException.class);
    thrown.expectMessage("syntax error at ', //other:lib )'");
    QueryParser.parse(query, queryEnvironment);
  }

  @Test
  public void shouldThrowExceptionWhenMissingParens() throws QueryException {
    String query = "testsof(deps(set('//foo:bar' //other:lib))";
    thrown.expect(QueryException.class);
    thrown.expectMessage("premature end of input");
    QueryParser.parse(query, queryEnvironment);
  }

  @Test
  public void shouldThrowExceptionWhenExtraParens() throws QueryException {
    String query = "testsof(deps(set('//foo:bar' //other:lib))))";
    thrown.expect(QueryException.class);
    thrown.expectMessage(
        "Unexpected token ')' after query expression 'testsof(deps(set(//foo:bar //other:lib)))'");
    QueryParser.parse(query, queryEnvironment);
  }

  @Test
  public void testUsefulErrorOnInsufficientArguments() throws QueryException {
    thrown.expect(QueryException.class);
    thrown.expectCause(Matchers.isA(QueryException.class));
    thrown.expectMessage("rdeps(EXPRESSION, EXPRESSION [, INTEGER ])");
    thrown.expectMessage("https://buck.build/command/query.html#rdeps");
    String query = "rdeps('')";
    QueryParser.parse(query, queryEnvironment);
  }

  @Test
  public void testUsefulErrorOnIncorrectArguments() throws QueryException {
    thrown.expect(QueryException.class);
    thrown.expectCause(Matchers.isA(QueryException.class));
    thrown.expectMessage("expected an integer literal");
    thrown.expectMessage("deps(EXPRESSION [, INTEGER [, EXPRESSION ] ])");
    thrown.expectMessage("https://buck.build/command/query.html#deps");
    String query = "deps(//foo:bar, //bar:foo)";
    QueryParser.parse(query, queryEnvironment);
  }

  private static class TestTargetEvaluator implements TargetEvaluator {

    @Override
    public ImmutableSet<ConfiguredQueryTarget> evaluateTarget(String target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Type getType() {
      return TargetEvaluator.Type.LAZY;
    }
  }
}
