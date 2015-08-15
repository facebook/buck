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

package com.facebook.buck.intellij.plugin.lang;
import com.intellij.lexer.*;
import com.intellij.psi.tree.IElementType;
import static com.facebook.buck.intellij.plugin.lang.psi.BuckTypes.*;

%%

%{
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
WHITE_SPACE=({LINE_WS}|{EOL})+

BOOLEAN=(True|False)
LINE_COMMENT=#.*
GLOB_KEYWORD=(glob|subdir_glob)
MACROS=[A-Z_]([A-Z0-9_])+
DOUBLE_QUOTED_STRING=\"([^\\\"\r\n]|\\[^\r\n])*\"?
SINGLE_QUOTED_STRING='([^\\'\r\n]|\\[^\r\n])*'?
NUMBER=-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]*)?
IDENTIFIER=[:jletter:] [:jletterdigit:]*

%%
<YYINITIAL> {
  {WHITE_SPACE}               { return com.intellij.psi.TokenType.WHITE_SPACE; }

  "None"                      { return NONE; }
  ","                         { return COMMA; }
  "="                         { return EQUAL; }
  "\\"                        { return SLASH; }
  "+"                         { return PLUS; }
  "excludes"                  { return GLOB_EXCLUDES_KEYWORD; }
  "("                         { return L_PARENTHESES; }
  "["                         { return L_BRACKET; }
  ")"                         { return R_PARENTHESES; }
  "]"                         { return R_BRACKET; }

  {BOOLEAN}                   { return BOOLEAN; }
  {LINE_COMMENT}              { return LINE_COMMENT; }
  {GLOB_KEYWORD}              { return GLOB_KEYWORD; }
  {MACROS}                    { return MACROS; }
  {DOUBLE_QUOTED_STRING}      { return DOUBLE_QUOTED_STRING; }
  {SINGLE_QUOTED_STRING}      { return SINGLE_QUOTED_STRING; }
  {NUMBER}                    { return NUMBER; }
  {IDENTIFIER}                { return IDENTIFIER; }

  [^] { return com.intellij.psi.TokenType.BAD_CHARACTER; }
}
