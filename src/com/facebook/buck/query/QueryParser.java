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

import static com.facebook.buck.query.Lexer.BINARY_OPERATORS;
import static com.facebook.buck.query.Lexer.TokenKind;

import com.facebook.buck.query.QueryEnvironment.Argument;
import com.facebook.buck.query.QueryEnvironment.ArgumentType;
import com.facebook.buck.query.QueryEnvironment.QueryFunction;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * LL(1) recursive descent parser for the Blaze query language, revision 2.
 *
 * <p>In the grammar below, non-terminals are lowercase and terminals are uppercase, or character
 * literals.
 *
 * <pre>
 * expr ::= WORD
 *        | '(' expr ')'
 *        | WORD '(' expr ( ',' expr ) * ')'
 *        | expr INTERSECT expr
 *        | expr '^' expr
 *        | expr UNION expr
 *        | expr '+' expr
 *        | expr EXCEPT expr
 *        | expr '-' expr
 *        | SET '(' WORD * ')'
 * </pre>
 */
final class QueryParser {

  private Lexer.Token token; // current lookahead token
  private final List<Lexer.Token> tokens;
  private final Iterator<Lexer.Token> tokenIterator;
  private final Map<String, QueryFunction> functions;
  private final QueryEnvironment.TargetEvaluator targetEvaluator;

  /** Scan and parse the specified query expression. */
  static QueryExpression parse(String query, QueryEnvironment env) throws QueryException {
    QueryParser parser = new QueryParser(Lexer.scan(query.toCharArray()), env);
    QueryExpression expr = parser.parseExpression();
    if (parser.token.kind != TokenKind.EOF) {
      throw new QueryException(
          "Unexpected token '%s' after query expression '%s'", parser.token, expr);
    }
    return expr;
  }

  private QueryParser(List<Lexer.Token> tokens, QueryEnvironment env) {
    this.functions = new HashMap<>();
    for (QueryFunction queryFunction : env.getFunctions()) {
      this.functions.put(queryFunction.getName(), queryFunction);
    }
    this.targetEvaluator = env.getTargetEvaluator();
    this.tokens = tokens;
    this.tokenIterator = tokens.iterator();
    nextToken();
  }

  /** Returns an exception. Don't forget to throw it. */
  private QueryException syntaxError(Lexer.Token token) {
    String message = "premature end of input";
    if (token.kind != TokenKind.EOF) {
      StringBuilder buf = new StringBuilder("syntax error at '");
      String sep = "";
      for (int index = tokens.indexOf(token),
              max = Math.min(tokens.size() - 1, index + 3); // 3 tokens of context
          index < max;
          ++index) {
        buf.append(sep).append(tokens.get(index));
        sep = " ";
      }
      buf.append("'");
      message = buf.toString();
    }
    return new QueryException(message);
  }

  private QueryException syntaxError(QueryException cause, QueryFunction function) {
    ImmutableList<ArgumentType> mandatoryArguments =
        function.getArgumentTypes().subList(0, function.getMandatoryArguments());
    ImmutableList<ArgumentType> optionalArguments =
        function
            .getArgumentTypes()
            .subList(function.getMandatoryArguments(), function.getArgumentTypes().size());
    StringBuilder argumentsString = new StringBuilder();
    Joiner.on(", ").appendTo(argumentsString, mandatoryArguments);
    if (optionalArguments.size() > 0) {
      argumentsString.append(" [, ");
      Joiner.on(" [, ").appendTo(argumentsString, optionalArguments);
      for (int i = 0; i < optionalArguments.size(); i++) {
        argumentsString.append(" ]");
      }
    }
    return new QueryException(
        cause,
        "`%s` when parsing call to the function `%s(%s)`.  Please see "
            + "https://buckbuild.com/command/query.html#%s for complete documentation.",
        cause.getMessage(),
        function.getName(),
        argumentsString.toString(),
        function.getName());
  }

  /**
   * Consumes the current token. If it is not of the specified (expected) kind, throws
   * QueryException. Returns the value associated with the consumed token, if any.
   */
  @Nullable
  private String consume(TokenKind kind) throws QueryException {
    if (token.kind != kind) {
      throw syntaxError(token);
    }
    String word = token.word;
    nextToken();
    return word;
  }

  /**
   * Consumes the current token, which must be a WORD containing an integer literal. Returns that
   * integer, or throws a QueryException otherwise.
   */
  private int consumeIntLiteral() throws QueryException {
    String intString = consume(TokenKind.WORD);
    try {
      return Integer.parseInt(intString);
    } catch (NumberFormatException e) {
      throw new QueryException("expected an integer literal but got '%s'", intString);
    }
  }

  private void nextToken() {
    if (token == null || token.kind != TokenKind.EOF) {
      token = tokenIterator.next();
    }
  }

  /**
   * expr ::= primary | expr INTERSECT expr | expr '^' expr | expr UNION expr | expr '+' expr | expr
   * EXCEPT expr | expr '-' expr
   */
  private QueryExpression parseExpression() throws QueryException {
    // All operators are left-associative and of equal precedence.
    return parseBinaryOperatorTail(parsePrimary());
  }

  /**
   * tail ::= ( <op> <primary> )* All operators have equal precedence. This factoring is required
   * for left-associative binary operators in LL(1).
   */
  private QueryExpression parseBinaryOperatorTail(QueryExpression lhs) throws QueryException {
    if (!BINARY_OPERATORS.contains(token.kind)) {
      return lhs;
    }

    List<QueryExpression> operands = new ArrayList<>();
    operands.add(lhs);
    TokenKind lastOperator = token.kind;

    while (BINARY_OPERATORS.contains(token.kind)) {
      TokenKind operator = token.kind;
      consume(operator);
      if (operator != lastOperator) {
        lhs = BinaryOperatorExpression.of(lastOperator, operands);
        operands.clear();
        operands.add(lhs);
        lastOperator = operator;
      }
      QueryExpression rhs = parsePrimary();
      operands.add(rhs);
    }
    return BinaryOperatorExpression.of(lastOperator, operands);
  }

  /**
   * primary ::= WORD | LET WORD = expr IN expr | '(' expr ')' | WORD '(' expr ( ',' expr ) * ')' |
   * DEPS '(' expr ')' | DEPS '(' expr ',' WORD ')' | RDEPS '(' expr ',' expr ')' | RDEPS '(' expr
   * ',' expr ',' WORD ')' | SET '(' WORD * ')'
   */
  private QueryExpression parsePrimary() throws QueryException {
    switch (token.kind) {
      case WORD:
        {
          String word = consume(TokenKind.WORD);
          if (token.kind == TokenKind.LPAREN) {
            QueryFunction function = functions.get(word);
            if (function == null) {
              throw new QueryException(syntaxError(token), "Unknown function '%s'", word);
            }
            ImmutableList.Builder<Argument> argsBuilder = ImmutableList.builder();
            consume(TokenKind.LPAREN);
            int argsSeen = 0;
            for (ArgumentType type : function.getArgumentTypes()) {

              // If the next token is a `)` and we've seen all mandatory args, then break out.
              if (token.kind == TokenKind.RPAREN && argsSeen >= function.getMandatoryArguments()) {
                break;
              }

              // Parse the individual arguments.
              try {
                switch (type) {
                  case EXPRESSION:
                    argsBuilder.add(Argument.of(parseExpression()));
                    break;

                  case WORD:
                    argsBuilder.add(
                        Argument.of(Preconditions.checkNotNull(consume(TokenKind.WORD))));
                    break;

                  case INTEGER:
                    argsBuilder.add(Argument.of(consumeIntLiteral()));
                    break;

                  default:
                    throw new IllegalStateException();
                }
              } catch (QueryException e) {
                throw syntaxError(e, function);
              }

              // If the next argument is a `,`, consume it before continuing to parsing the next
              // argument.
              if (token.kind == TokenKind.COMMA) {
                consume(TokenKind.COMMA);
              }

              argsSeen++;
            }

            consume(TokenKind.RPAREN);
            return FunctionExpression.of(function, argsBuilder.build());
          } else {
            Preconditions.checkNotNull(word);
            if (targetEvaluator.getType() == QueryEnvironment.TargetEvaluator.Type.LAZY) {
              return TargetLiteral.of(word);
            } else {
              return TargetSetExpression.of(targetEvaluator.evaluateTarget(word));
            }
          }
        }
      case LPAREN:
        {
          consume(TokenKind.LPAREN);
          QueryExpression expr = parseExpression();
          consume(TokenKind.RPAREN);
          return expr;
        }
      case SET:
        {
          nextToken();
          consume(TokenKind.LPAREN);
          ImmutableList.Builder<String> wordsBuilder = ImmutableList.builder();
          while (token.kind == TokenKind.WORD) {
            wordsBuilder.add(Preconditions.checkNotNull(consume(TokenKind.WORD)));
          }
          consume(TokenKind.RPAREN);

          if (targetEvaluator.getType() == QueryEnvironment.TargetEvaluator.Type.LAZY) {
            return SetExpression.of(
                wordsBuilder.build().stream().map(TargetLiteral::of).collect(Collectors.toList()));
          } else {
            ImmutableSet.Builder<QueryTarget> targets = ImmutableSet.builder();
            for (String word : wordsBuilder.build()) {
              targets.addAll(targetEvaluator.evaluateTarget(word));
            }
            return TargetSetExpression.of(targets.build());
          }
        }
        // $CASES-OMITTED$
      default:
        throw syntaxError(token);
    }
  }
}
