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
import com.google.common.collect.Lists;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.List;

public class LexerTest {

  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testScan() throws Exception {
    String query = "deps(//foo:bar)";
    List<Lexer.Token> tokens = Lexer.scan(query.toCharArray());
    List<Lexer.Token> expected = Lists.newArrayList(
        new Lexer.Token("deps"),
        new Lexer.Token(Lexer.TokenKind.LPAREN),
        new Lexer.Token("//foo:bar"),
        new Lexer.Token(Lexer.TokenKind.RPAREN),
        new Lexer.Token(Lexer.TokenKind.EOF)
    );
    assertThat(tokens, is(equalTo(expected)));
  }

  @Test
  public void testIgnoreWhitespaceChars() throws Exception {
    String query = " ( ) a\nb \rc +-/=,^ @.";
    List<Lexer.Token> tokens = Lexer.scan(query.toCharArray());
    List<Lexer.Token> expected = Lists.newArrayList(
        new Lexer.Token(Lexer.TokenKind.LPAREN),
        new Lexer.Token(Lexer.TokenKind.RPAREN),
        new Lexer.Token("a"),
        new Lexer.Token("b"),
        new Lexer.Token("c"),
        new Lexer.Token(Lexer.TokenKind.PLUS),
        new Lexer.Token(Lexer.TokenKind.MINUS),
        new Lexer.Token("/"),
        new Lexer.Token(Lexer.TokenKind.EQUALS),
        new Lexer.Token(Lexer.TokenKind.COMMA),
        new Lexer.Token(Lexer.TokenKind.CARET),
        new Lexer.Token("@."),
        new Lexer.Token(Lexer.TokenKind.EOF)
    );
    assertThat(tokens, is(equalTo(expected)));
  }

  @Test
  public void shouldThrowExceptionWhenUnclosedSingleQuote() throws QueryException {
    String query = "deps('//foo:bar') + 'foo - bar";
    thrown.expect(QueryException.class);
    thrown.expectMessage("unclosed quotation");
    Lexer.scan(query.toCharArray());
  }

  @Test
  public void shouldThrowExceptionWhenUnclosedDoubleQuote() throws QueryException {
    String query = "deps('//foo:bar') + \"foo - bar";
    thrown.expect(QueryException.class);
    thrown.expectMessage("unclosed quotation");
    Lexer.scan(query.toCharArray());
  }
}
