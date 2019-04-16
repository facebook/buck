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

EOL=\r|\n|\r\n
LINE_WS=[\ \t\f]|\\(\r|\n|\r\n)

BOOLEAN=(True|False)
LINE_COMMENT=#[^\r\n]*

ANY_ESCAPE_SEQUENCE = \\[^]

APOSTROPHED_STRING = \'([^\\\'\r\n]|\\[^\r\n]|\\[\r\n])*\'?
APOSTROPHED_RAW_STRING = r\'[^\'\r\n]*\'?

QUOTED_STRING = \"([^\\\"\r\n]|\\[^\r\n]|\\[\r\n])*\"?
QUOTED_RAW_STRING = r\"[^\"\r\n]*\"?

THREE_APOSTROPHES = \'\'\'
TRIPLE_APOSTROPHED_STRING_PIECES = {ANY_ESCAPE_SEQUENCE} | [^\\\'] | (\'[^\']) | (\'\'[^\'])
TRIPLE_APOSTROPHED_STRING = {THREE_APOSTROPHES} {TRIPLE_APOSTROPHED_STRING_PIECES}* {THREE_APOSTROPHES}?
TRIPLE_APOSTROPHED_RAW_STRING_PIECES = [^\'] | (\'[^\']) | (\'\'[^\'])
TRIPLE_APOSTROPHED_RAW_STRING = r{THREE_APOSTROPHES} {TRIPLE_APOSTROPHED_RAW_STRING_PIECES}* {THREE_APOSTROPHES}?

THREE_QUOTES = \"\"\"
TRIPLE_QUOTED_STRING_PIECES = {ANY_ESCAPE_SEQUENCE} | [^\\\"] | (\"[^\"]) | (\"\"[^\"])
TRIPLE_QUOTED_STRING = {THREE_QUOTES} {TRIPLE_QUOTED_STRING_PIECES}* {THREE_QUOTES}?
TRIPLE_QUOTED_RAW_STRING_PIECES = [^\"] | (\"[^\"]) | (\"\"[^\"])
TRIPLE_QUOTED_RAW_STRING = r{THREE_QUOTES} {TRIPLE_QUOTED_RAW_STRING_PIECES}* {THREE_QUOTES}?

UPDATE_OPS=\+=|-=|\*=|"/"=|"//"=|%=|&=|\|=|\^=|<<=|>>=

HEX_LITERAL = 0[xX][0-9A-Fa-f]+
OCTAL_LITERAL = 0[oO][0-7]+
DECIMAL_LITERAL = 0|([1-9][0-9]*)

// TODO: Starlark says identifiers can be:
// "unicode letters, digits, and underscores, not starting with a digit"
IDENTIFIER_TOKEN=[a-zA-Z_]([a-zA-Z0-9_])*

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
  // As per https://github.com/bazelbuild/starlark/blob/master/spec.md
  // "The following tokens are keywords and may not be used as identifiers:"
  "and"                       { return AND; }
  "break"                     { return BREAK; }
  "continue"                  { return CONTINUE; }
  "def"                       { return DEF; }
  "elif"                      { return ELIF; }
  "else"                      { return ELSE; }
  "for"                       { return FOR; }
  "if"                        { return IF; }
  "in"                        { return IN; }
  "load"                      { return LOAD; }
  "not"                       { return NOT; }
  "or"                        { return OR; }
  "pass"                      { return PASS; }
  "return"                    { return RETURN; }
  // As per https://github.com/bazelbuild/starlark/blob/master/spec.md
  // "The tokens below also may not be used as identifiers
  // although they do not appear in the grammar;
  // they are reserved as possible future keywords:"
  "as"                        { return AS; }
  "assert"                    { return ASSERT; }
  "class"                     { return CLASS; }
  "del"                       { return DEL; }
  "except"                    { return EXCEPT; }
  "finally"                   { return FINALLY; }
  "from"                      { return FROM; }
  "global"                    { return GLOBAL; }
  "import"                    { return IMPORT; }
  "is"                        { return IS; }
  "lambda"                    { return LAMBDA; }
  "nonlocal"                  { return NONLOCAL; }
  "raise"                     { return RAISE; }
  "try"                       { return TRY; }
  "while"                     { return WHILE; }
  "with"                      { return WITH; }
  "yield"                     { return YIELD; }

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
  {APOSTROPHED_STRING}             { return APOSTROPHED_STRING; }
  {APOSTROPHED_RAW_STRING}         { return APOSTROPHED_RAW_STRING; }
  {TRIPLE_APOSTROPHED_STRING}      { return TRIPLE_APOSTROPHED_STRING; }
  {TRIPLE_APOSTROPHED_RAW_STRING}  { return TRIPLE_APOSTROPHED_RAW_STRING; }
  {QUOTED_STRING}                  { return QUOTED_STRING; }
  {QUOTED_RAW_STRING}              { return QUOTED_RAW_STRING; }
  {TRIPLE_QUOTED_STRING}           { return TRIPLE_QUOTED_STRING; }
  {TRIPLE_QUOTED_RAW_STRING}       { return TRIPLE_QUOTED_RAW_STRING; }
  {LINE_COMMENT}              { return LINE_COMMENT; }
  {HEX_LITERAL}               { return HEX_LITERAL; }
  {OCTAL_LITERAL}             { return OCTAL_LITERAL; }
  {DECIMAL_LITERAL}           { return DECIMAL_LITERAL; }
  {IDENTIFIER_TOKEN}          { return IDENTIFIER_TOKEN; }

  [^]                         { return BAD_CHARACTER; }
  <<EOF>>                     {
                                if (!stack.isEmpty()) {
                                  stack.pop();
                                  return DEDENT;
                                } else {
                                  return null;
                                }
                              }
}

<INDENTED> {
  [ ]                         { currentIndent++; }
  [\t]                        { currentIndent += 8 - currentIndent % 8; }
  {EOL}                       { currentIndent = 0; }
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
                                  return WHITE_SPACE;
                                }
                              }
  <<EOF>>                     {
                                yybegin(YYINITIAL);
                              }
}

