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

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;

/**
 * The environment of a Buck query that can evaluate queries to produce a result.
 *
 * <p>The query language is documented at docs/command/query.soy
 */
public interface QueryEnvironment {

  /** Type of an argument of a user-defined query function. */
  enum ArgumentType {
    EXPRESSION,
    WORD,
    INTEGER,
  }

  /** Value of an argument of a user-defined query function. */
  class Argument {

    private final ArgumentType type;

    @Nullable private final QueryExpression expression;

    @Nullable private final String word;

    private final int integer;

    private Argument(
        ArgumentType type,
        @Nullable QueryExpression expression,
        @Nullable String word,
        int integer) {
      this.type = type;
      this.expression = expression;
      this.word = word;
      this.integer = integer;
    }

    public static Argument of(QueryExpression expression) {
      return new Argument(ArgumentType.EXPRESSION, expression, null, 0);
    }

    public static Argument of(String word) {
      return new Argument(ArgumentType.WORD, null, word, 0);
    }

    public static Argument of(int integer) {
      return new Argument(ArgumentType.INTEGER, null, null, integer);
    }

    public ArgumentType getType() {
      return type;
    }

    public QueryExpression getExpression() {
      return Preconditions.checkNotNull(expression);
    }

    public String getWord() {
      return Preconditions.checkNotNull(word);
    }

    public int getInteger() {
      return integer;
    }

    @Override
    public String toString() {
      switch (type) {
        case WORD:
          return "'" + word + "'";
        case EXPRESSION:
          return Preconditions.checkNotNull(expression).toString();
        case INTEGER:
          return Integer.toString(integer);
        default:
          throw new IllegalStateException();
      }
    }

    @Override
    public boolean equals(Object other) {
      return (other instanceof Argument) && equalTo((Argument) other);
    }

    public boolean equalTo(Argument other) {
      return type.equals(other.type)
          && integer == other.integer
          && Objects.equals(expression, other.expression)
          && Objects.equals(word, other.word);
    }

    @Override
    public int hashCode() {
      int h = 31;
      h = h * 17 + type.hashCode();
      h = h * 17 + integer;
      if (expression != null) {
        h = h * 17 + expression.hashCode();
      }
      if (word != null) {
        h = h * 17 + word.hashCode();
      }
      return h;
    }
  }

  /** A user-defined query function. */
  interface QueryFunction {
    /** Name of the function as it appears in the query language. */
    String getName();

    /**
     * The number of arguments that are required. The rest is optional.
     *
     * <p>This should be greater than or equal to zero and at smaller than or equal to the length of
     * the list returned by {@link #getArgumentTypes}.
     */
    int getMandatoryArguments();

    /** The types of the arguments of the function. */
    ImmutableList<ArgumentType> getArgumentTypes();

    /**
     * Called when a user-defined function is to be evaluated.
     *
     * @param evaluator the evaluator for evaluating argument expressions.
     * @param env the query environment this function is evaluated in.
     * @param args the input arguments. These are type-checked against the specification returned by
     *     {@link #getArgumentTypes} and {@link #getMandatoryArguments}
     */
    ImmutableSet<QueryTarget> eval(
        QueryEvaluator evaluator, QueryEnvironment env, ImmutableList<Argument> args)
        throws QueryException;
  }

  /**
   * A procedure for evaluating a target literal to {@link QueryTarget}. This evaluation can either
   * happen immediately at parse time or be delayed until evalution of the entire query.
   */
  interface TargetEvaluator {
    /** Returns the set of target nodes for the specified target pattern, in 'buck build' syntax. */
    ImmutableSet<QueryTarget> evaluateTarget(String target) throws QueryException;

    Type getType();

    enum Type {
      IMMEDIATE,
      LAZY
    }
  }

  /** Returns an evaluator for target patterns. */
  TargetEvaluator getTargetEvaluator();

  /**
   * Returns the set of target nodes in the graph for the specified target pattern, in 'buck build'
   * syntax.
   */
  default ImmutableSet<QueryTarget> getTargetsMatchingPattern(String pattern)
      throws QueryException {
    return getTargetEvaluator().evaluateTarget(pattern);
  }

  /** Returns the direct forward dependencies of the specified targets. */
  ImmutableSet<QueryTarget> getFwdDeps(Iterable<QueryTarget> targets) throws QueryException;

  /**
   * Applies {@code action} to each forward dependencies of the specified targets.
   *
   * <p>Might apply more than once to the same target, so {@code action} should be idempotent.
   */
  default void forEachFwdDep(Iterable<QueryTarget> targets, Consumer<? super QueryTarget> action)
      throws QueryException {
    getFwdDeps(targets).forEach(action);
  }

  /** Returns the direct reverse dependencies of the specified targets. */
  Set<QueryTarget> getReverseDeps(Iterable<QueryTarget> targets) throws QueryException;

  Set<QueryTarget> getInputs(QueryTarget target) throws QueryException;

  /**
   * Returns the forward transitive closure of all of the targets in "targets". Callers must ensure
   * that {@link #buildTransitiveClosure} has been called for the relevant subgraph.
   */
  Set<QueryTarget> getTransitiveClosure(Set<QueryTarget> targets) throws QueryException;

  /**
   * Construct the dependency graph for a depth-bounded forward transitive closure of all nodes in
   * "targetNodes". The identity of the calling expression is required to produce error messages.
   *
   * <p>If a larger transitive closure was already built, returns it to improve incrementality,
   * since all depth-constrained methods filter it after it is built anyway.
   */
  void buildTransitiveClosure(Set<QueryTarget> targetNodes, int maxDepth) throws QueryException;

  String getTargetKind(QueryTarget target) throws QueryException;

  /** Returns the tests associated with the given target. */
  ImmutableSet<QueryTarget> getTestsForTarget(QueryTarget target) throws QueryException;

  /** Returns the build files that define the given targets. */
  ImmutableSet<QueryTarget> getBuildFiles(Set<QueryTarget> targets) throws QueryException;

  /** Returns the targets that own one or more of the given files. */
  ImmutableSet<QueryTarget> getFileOwners(ImmutableList<String> files) throws QueryException;

  /** Returns the existing targets in the value of `attribute` of the given `target`. */
  ImmutableSet<QueryTarget> getTargetsInAttribute(QueryTarget target, String attribute)
      throws QueryException;

  /** Returns the objects in the `attribute` of the given `target` that satisfy `predicate` */
  ImmutableSet<Object> filterAttributeContents(
      QueryTarget target, String attribute, Predicate<Object> predicate) throws QueryException;

  /** Returns the set of query functions implemented by this query environment. */
  Iterable<QueryFunction> getFunctions();

  /** List of the default query functions. */
  List<QueryFunction> DEFAULT_QUERY_FUNCTIONS =
      ImmutableList.of(
          new AllPathsFunction(),
          new AttrFilterFunction(),
          new BuildFileFunction(),
          new DepsFunction(),
          new DepsFunction.FirstOrderDepsFunction(),
          new DepsFunction.LookupFunction(),
          new InputsFunction(),
          new FilterFunction(),
          new KindFunction(),
          new LabelsFunction(),
          new OwnerFunction(),
          new RdepsFunction(),
          new TestsOfFunction());

  /** @return the {@link QueryTarget}s expanded from the given variable {@code name}. */
  default ImmutableSet<QueryTarget> resolveTargetVariable(String name) {
    throw new IllegalArgumentException(String.format("unexpected target variable \"%s\"", name));
  }
}
