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

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.google.common.collect.ImmutableList;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class QueryParserTest {

  private FakeQueryEnvironment queryEnvironment = new FakeQueryEnvironment();;

  @Rule
  public TemporaryPaths tmp = new TemporaryPaths();

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testDeps() throws Exception {
    Argument arg = Argument.of(ArgumentType.EXPRESSION, TargetLiteral.of("//foo:bar"), null, 0);
    ImmutableList<Argument> args = ImmutableList.of(arg);
    QueryExpression expected = FunctionExpression.of(new DepsFunction(), args);

    String query = "deps('//foo:bar')";
    QueryExpression result = QueryParser.parse(query, queryEnvironment);
    assertThat(result, is(equalTo(expected)));
  }

  @Test
  public void testTestsOfDepsSet() throws Exception {
    ImmutableList<TargetLiteral> literals = ImmutableList.of(
        TargetLiteral.of("//foo:bar"),
        TargetLiteral.of("//other:lib"));
    Argument arg = Argument.of(ArgumentType.EXPRESSION, SetExpression.of(literals), null, 0);
    QueryExpression depsExpr = FunctionExpression.of(new DepsFunction(), ImmutableList.of(arg));

    Argument depsArg = Argument.of(ArgumentType.EXPRESSION, depsExpr, null, 0);
    QueryExpression testsofExpr =
        FunctionExpression.of(new TestsOfFunction(), ImmutableList.of(depsArg));

    String query = "testsof(deps(set('//foo:bar' //other:lib)))";
    QueryExpression result = QueryParser.parse(query, queryEnvironment);
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
}
