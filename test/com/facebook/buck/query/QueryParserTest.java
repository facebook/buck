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

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.TargetEvaluator;
import com.facebook.buck.testutil.TemporaryPaths;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.function.Predicate;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class QueryParserTest {

  private QueryEnvironment queryEnvironment;

  @Rule public TemporaryPaths tmp = new TemporaryPaths();

  @Rule public ExpectedException thrown = ExpectedException.none();

  @Before
  public void makeEnvironment() {
    QueryEnvironment.TargetEvaluator targetEvaluator = new TestTargetEvaluator();
    queryEnvironment = new TestQueryEnvironment(targetEvaluator);
  }

  @Test
  public void testDeps() throws Exception {
    ImmutableList<Argument<QueryBuildTarget>> args =
        ImmutableList.of(Argument.of(TargetLiteral.of("//foo:bar")));
    QueryExpression<QueryBuildTarget> expected =
        new ImmutableFunctionExpression<>(new DepsFunction(), args);

    String query = "deps('//foo:bar')";
    QueryExpression result = QueryParser.parse(query, queryEnvironment);
    assertThat(result, is(equalTo(expected)));
  }

  @Test
  public void testTestsOfDepsSet() throws Exception {
    ImmutableList<TargetLiteral<QueryBuildTarget>> args =
        ImmutableList.of(TargetLiteral.of("//foo:bar"), TargetLiteral.of("//other:lib"));
    QueryExpression<QueryBuildTarget> depsExpr =
        new ImmutableFunctionExpression<>(
            new DepsFunction(), ImmutableList.of(Argument.of(SetExpression.of(args))));
    QueryExpression<QueryBuildTarget> testsofExpr =
        new ImmutableFunctionExpression<>(
            new TestsOfFunction(), ImmutableList.of(Argument.of(depsExpr)));

    String query = "testsof(deps(set('//foo:bar' //other:lib)))";
    QueryExpression<QueryBuildTarget> result = QueryParser.parse(query, queryEnvironment);
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
    thrown.expectMessage("https://buckbuild.com/command/query.html#rdeps");
    String query = "rdeps('')";
    QueryParser.parse(query, queryEnvironment);
  }

  @Test
  public void testUsefulErrorOnIncorrectArguments() throws QueryException {
    thrown.expect(QueryException.class);
    thrown.expectCause(Matchers.isA(QueryException.class));
    thrown.expectMessage("expected an integer literal");
    thrown.expectMessage("deps(EXPRESSION [, INTEGER [, EXPRESSION ] ])");
    thrown.expectMessage("https://buckbuild.com/command/query.html#deps");
    String query = "deps(//foo:bar, //bar:foo)";
    QueryParser.parse(query, queryEnvironment);
  }

  private static class TestTargetEvaluator implements TargetEvaluator {

    @Override
    public ImmutableSet<QueryTarget> evaluateTarget(String target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Type getType() {
      return TargetEvaluator.Type.LAZY;
    }
  }

  private static class TestQueryEnvironment implements QueryEnvironment<QueryBuildTarget> {

    private final TargetEvaluator targetEvaluator;

    private TestQueryEnvironment(TargetEvaluator targetEvaluator) {
      this.targetEvaluator = targetEvaluator;
    }

    @Override
    public TargetEvaluator getTargetEvaluator() {
      return targetEvaluator;
    }

    @Override
    public Iterable<QueryFunction<?, QueryBuildTarget>> getFunctions() {
      return ImmutableList.of(new DepsFunction(), new RdepsFunction(), new TestsOfFunction());
    }

    @Override
    public ImmutableSet<QueryBuildTarget> getFwdDeps(Iterable<QueryBuildTarget> targets) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<QueryBuildTarget> getReverseDeps(Iterable<QueryBuildTarget> targets) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<QueryFileTarget> getInputs(QueryBuildTarget target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Set<QueryBuildTarget> getTransitiveClosure(Set<QueryBuildTarget> targets) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void buildTransitiveClosure(Set<? extends QueryTarget> targetNodes, int maxDepth) {
      throw new UnsupportedOperationException();
    }

    @Override
    public String getTargetKind(QueryBuildTarget target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<QueryBuildTarget> getTestsForTarget(QueryBuildTarget target) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<QueryFileTarget> getBuildFiles(Set<QueryBuildTarget> targets) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<QueryBuildTarget> getFileOwners(ImmutableList<String> files) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<? extends QueryTarget> getTargetsInAttribute(
        QueryBuildTarget target, String attribute) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ImmutableSet<Object> filterAttributeContents(
        QueryBuildTarget target, String attribute, Predicate<Object> predicate) {
      throw new UnsupportedOperationException();
    }
  }
}
