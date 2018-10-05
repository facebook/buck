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

package com.facebook.buck.intellij.ideabuck.lang;

import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import java.util.ArrayDeque;

import static com.intellij.psi.TokenType.BAD_CHARACTER;
import static com.intellij.psi.TokenType.WHITE_SPACE;
import static com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes.*;

%%

%{
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    int unmatchedPair = 0;
    int currentIndent = 0;
    public _BuckLexer() {
      this((java.io.Reader)null);
    }
%}

%public
%class _BuckLexer
%implements FlexLexer
%function advance
%type IElementType
%unicode

EOL="\r"|"\n"|"\r\n"
LINE_WS=[\ \t\f]

BOOLEAN=(True|False)
LINE_COMMENT=#.*

ANY_ESCAPE_SEQUENCE = \\[^]

ONE_OR_TWO_DOUBLE_QUOTES = (\"[^\\\"]) | (\"\\[^]) | (\"\"[^\\\"]) | (\"\"\\[^])
THREE_DOUBLE_QUOTES = (\"\"\")
DOUBLE_QUOTED_DOC_STRING_CHARS = [^\\\"] | {ANY_ESCAPE_SEQUENCE} | {ONE_OR_TWO_DOUBLE_QUOTES}

ONE_OR_TWO_SINGLE_QUOTES = (\'[^\\\']) | (\'\\[^]) | (\'\'[^\\\']) | (\'\'\\[^])
THREE_SINGLE_QUOTES = (\'\'\')
SINGLE_QUOTED_DOC_STRING_CHARS = [^\\\'] | {ANY_ESCAPE_SEQUENCE} | {ONE_OR_TWO_SINGLE_QUOTES}

DOUBLE_QUOTED_DOC_STRING = {THREE_DOUBLE_QUOTES} {DOUBLE_QUOTED_DOC_STRING_CHARS}* {THREE_DOUBLE_QUOTES}?
SINGLE_QUOTED_DOC_STRING = {THREE_SINGLE_QUOTES} {SINGLE_QUOTED_DOC_STRING_CHARS}* {THREE_SINGLE_QUOTES}?
DOUBLE_QUOTED_STRING=\"([^\\\"\r\n]|\\[^\r\n])*\"?
SINGLE_QUOTED_STRING='([^\\'\r\n]|\\[^\r\n])*'?

UPDATE_OPS=\+=|-=|\*=|"/"=|"//"=|%=|&=|\|=|\^=|<<=|>>=
NUMBER=-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]*)?
IDENTIFIER=[a-zA-Z_]([a-zA-Z0-9_])*

%x INDENTED

%%
<YYINITIAL> {
  ({EOL})+                    {
                                if (unmatchedPair == 0) {
                                  yybegin(INDENTED);
                                  currentIndent = 0;
                                }
                                return WHITE_SPACE;
                              }
  ({LINE_WS})+                { return WHITE_SPACE; }

  "None"                      { return NONE; }
  ","                         { return COMMA; }
  "="                         { return EQUAL; }
  "=="                        { return DOUBLE_EQUAL; }
  "!="                        { return NOT_EQUAL; }
  ">"                         { return GREATER_THAN; }
  "<"                         { return LESS_THAN; }
  ">="                        { return GREATER_EQUAL; }
  "<="                        { return LESS_EQUAL; }
  "|"                         { return BIT_OR; }
  "&"                         { return BIT_AND; }
  "^"                         { return BIT_XOR; }
  "\\"                        { return SLASH; }
  ":"                         { return COLON; }
  "+"                         { return PLUS; }
  "-"                         { return MINUS; }
  "/"                         { return DIVISION; }
  "//"                        { return DIVISION_INT; }
  "."                         { return DOT; }
  ";"                         { return SEMI_COLON; }
  "*"                         { return STAR; }
  "**"                        { return DOUBLE_STAR; }
  "load"                      { return LOAD_KEYWORD; }
  "def"                       { return DEF; }
  "for"                       { return FOR; }
  "in"                        { return IN; }
  "and"                       { return AND; }
  "or"                        { return OR; }
  "not"                       { return NOT; }
  "if"                        { return IF; }
  "else"                      { return ELSE; }
  "elif"                      { return ELIF; }
  "break"                     { return BREAK; }
  "continue"                  { return CONTINUE; }
  "pass"                      { return PASS; }
  "return"                    { return RETURN; }
  "lambda"                    { return LAMBDA; }
  "glob"                      { return GLOB; }
  "("                         {
                                unmatchedPair++;
                                return L_PARENTHESES;
                              }
  "["                         {
                                unmatchedPair++;
                                return L_BRACKET;
                              }
  "{"                         {
                                unmatchedPair++;
                                return L_CURLY;
                              }
  ")"                         {
                                unmatchedPair--;
                                return R_PARENTHESES;
                              }
  "]"                         {
                                unmatchedPair--;
                                return R_BRACKET;
                              }
  "}"                         {
                                unmatchedPair--;
                                return R_CURLY;
                              }
  "%"                         { return PERCENT; }

  {BOOLEAN}                   { return BOOLEAN; }
  {UPDATE_OPS}                { return UPDATE_OPS; }
  {DOUBLE_QUOTED_DOC_STRING}  { return DOUBLE_QUOTED_DOC_STRING; }
  {SINGLE_QUOTED_DOC_STRING}  { return SINGLE_QUOTED_DOC_STRING; }
  {LINE_COMMENT}              { return LINE_COMMENT; }
  {DOUBLE_QUOTED_STRING}      { return DOUBLE_QUOTED_STRING; }
  {SINGLE_QUOTED_STRING}      { return SINGLE_QUOTED_STRING; }
  {NUMBER}                    { return NUMBER; }
  {IDENTIFIER}                { return IDENTIFIER; }

  [^]                         { return BAD_CHARACTER; }
}

<INDENTED> {
  [ ]                         { currentIndent++; }
  [\t]                        { currentIndent += 8 - currentIndent % 8; }
  {EOL}                       { currentIndent = 0; }
  {LINE_COMMENT}              {
                                yybegin(YYINITIAL);
                                return LINE_COMMENT;
                              }
  [^]                         {
                                yypushback(1);
                                int top = stack.isEmpty() ? 0 : stack.peek();
                                if (currentIndent > top) {
                                  stack.push(currentIndent);
                                  yybegin(YYINITIAL);
                                  return INDENT;
                                } else if (currentIndent < top) {
                                  stack.pop();
                                  return DEDENT;
                                } else {
                                  yybegin(YYINITIAL);
                                }
                              }
  <<EOF>>                     {
                                if (!stack.isEmpty()) {
                                  stack.pop();
                                  return DEDENT;
                                } else {
                                  yybegin(YYINITIAL);
                                }
                              }
}

