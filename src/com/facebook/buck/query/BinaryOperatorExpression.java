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

import static com.facebook.buck.query.Lexer.TokenKind;

import com.facebook.buck.core.model.QueryTarget;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A binary algebraic set operation.
 *
 * <pre>
 * expr ::= expr (INTERSECT expr)+
 *        | expr ('^' expr)+
 *        | expr (UNION expr)+
 *        | expr ('+' expr)+
 *        | expr (EXCEPT expr)+
 *        | expr ('-' expr)+
 * </pre>
 */
final class BinaryOperatorExpression<NODE_TYPE> extends QueryExpression<NODE_TYPE> {
  enum Operator {
    INTERSECT("^"),
    UNION("+"),
    EXCEPT("-");

    private final String prettyName;

    Operator(String prettyName) {
      this.prettyName = prettyName;
    }

    @Override
    public String toString() {
      return prettyName;
    }

    private static Operator from(TokenKind operator) {
      switch (operator) {
        case INTERSECT:
        case CARET:
          return INTERSECT;
        case UNION:
        case PLUS:
          return UNION;
        case EXCEPT:
        case MINUS:
          return EXCEPT;
          // $CASES-OMITTED$
        default:
          throw new IllegalArgumentException("operator=" + operator);
      }
    }
  }

  private final Operator operator;
  private final ImmutableList<QueryExpression<NODE_TYPE>> operands;
  private final int hash;

  private BinaryOperatorExpression(
      Operator operator, ImmutableList<QueryExpression<NODE_TYPE>> operands) {
    Preconditions.checkState(operands.size() > 1);
    this.operator = operator;
    this.operands = operands;
    this.hash = Objects.hash(operator, operands);
  }

  Operator getOperator() {
    return operator;
  }

  ImmutableList<QueryExpression<NODE_TYPE>> getOperands() {
    return operands;
  }

  static <T> BinaryOperatorExpression<T> of(TokenKind operator, List<QueryExpression<T>> operands) {
    return BinaryOperatorExpression.of(Operator.from(operator), ImmutableList.copyOf(operands));
  }

  static <T> BinaryOperatorExpression<T> of(Operator operator, List<QueryExpression<T>> operands) {
    return new BinaryOperatorExpression<>(operator, ImmutableList.copyOf(operands));
  }

  @Override
  @SuppressWarnings("unchecked")
  <OUTPUT_TYPE extends QueryTarget> Set<OUTPUT_TYPE> eval(
      QueryEvaluator<NODE_TYPE> evaluator, QueryEnvironment<NODE_TYPE> env) throws QueryException {
    ImmutableList<QueryExpression<NODE_TYPE>> operands = getOperands();
    Set<QueryTarget> lhsValue = new LinkedHashSet<>(evaluator.eval(operands.get(0), env));

    for (int i = 1; i < operands.size(); i++) {
      Set<QueryTarget> rhsValue = evaluator.eval(operands.get(i), env);
      switch (getOperator()) {
        case INTERSECT:
          lhsValue.retainAll(rhsValue);
          break;
        case UNION:
          lhsValue.addAll(rhsValue);
          break;
        case EXCEPT:
          lhsValue.removeAll(rhsValue);
          break;
        default:
          throw new IllegalStateException("operator=" + getOperator());
      }
    }
    return (Set<OUTPUT_TYPE>) lhsValue;
  }

  @Override
  public void traverse(QueryExpression.Visitor<NODE_TYPE> visitor) {
    if (visitor.visit(this) == VisitResult.CONTINUE) {
      for (QueryExpression<NODE_TYPE> subExpression : getOperands()) {
        subExpression.traverse(visitor);
      }
    }
  }

  @Override
  public int hashCode() {
    return hash;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof BinaryOperatorExpression)) {
      return false;
    }

    BinaryOperatorExpression<?> that = (BinaryOperatorExpression<?>) obj;
    return Objects.equals(this.operator, that.operator)
        && Objects.equals(this.operands, that.operands);
  }

  @Override
  public String toString() {
    ImmutableList<QueryExpression<NODE_TYPE>> operands = getOperands();
    StringBuilder result = new StringBuilder();
    for (int i = 1; i < operands.size(); i++) {
      result.append("(");
    }
    result.append(operands.get(0));
    for (int i = 1; i < operands.size(); i++) {
      result.append(" ").append(getOperator()).append(" ").append(operands.get(i)).append(")");
    }
    return result.toString();
  }
}
