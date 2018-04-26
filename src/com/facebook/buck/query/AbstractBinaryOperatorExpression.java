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

import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.immutables.value.Value;

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
@Value.Immutable(prehash = true)
@BuckStyleTuple
abstract class AbstractBinaryOperatorExpression extends QueryExpression {
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

  abstract Operator getOperator();

  abstract ImmutableList<QueryExpression> getOperands();

  protected static BinaryOperatorExpression of(TokenKind operator, List<QueryExpression> operands) {
    return BinaryOperatorExpression.of(Operator.from(operator), operands);
  }

  @Value.Check
  protected void check() {
    Preconditions.checkState(getOperands().size() > 1);
  }

  @Override
  ImmutableSet<QueryTarget> eval(QueryEvaluator evaluator, QueryEnvironment env)
      throws QueryException {
    ImmutableList<QueryExpression> operands = getOperands();
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
    return ImmutableSet.copyOf(lhsValue);
  }

  @Override
  public void traverse(QueryExpression.Visitor visitor) {
    if (visitor.visit(this) == VisitResult.CONTINUE) {
      for (QueryExpression subExpression : getOperands()) {
        subExpression.traverse(visitor);
      }
    }
  }

  @Override
  public String toString() {
    ImmutableList<QueryExpression> operands = getOperands();
    StringBuilder result = new StringBuilder();
    for (int i = 1; i < operands.size(); i++) {
      result.append("(");
    }
    result.append(operands.get(0));
    for (int i = 1; i < operands.size(); i++) {
      result.append(" " + getOperator() + " " + operands.get(i) + ")");
    }
    return result.toString();
  }
}
