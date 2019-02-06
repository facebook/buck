/*
 * Copyright 2019-present Facebook, Inc.
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

import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTokenType;
import com.intellij.lexer.FlexLexer;
import com.intellij.psi.tree.IElementType;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTypes;
import com.intellij.psi.TokenType;

%%

%class _BcfgLexer
%implements FlexLexer
%unicode
%function advance
%type IElementType
%eof{  return;
%eof}

EOL = \r|\n|\r\n
WHITE_SPACE = [\ \t\f]
LINE_CONTINUATION = \\(\r|\n|\r\n)
LINE_CONTINUATION_PLUS_INDENT = {LINE_CONTINUATION}{WHITE_SPACE}*
COMMENT = [#;][^\r\n]*

SECTION_NAME = [A-Za-z_][-A-Za-z0-9_#.]*
PROPERTY_NAME = [A-Za-z_][-A-Za-z0-9_#.]*
PROPERTY_VALUE_FRAGMENT = [^\s]([^\\\r\n]|\\[^\r\n])*
ASSIGN = [:=]
REQUIRED_FILE = "<file:"
OPTIONAL_FILE = "<?file:"

%state IN_SECTION_HEADER
%state IN_PROPERTY_VALUE
%state IN_FILE_PATH

%%

<YYINITIAL> {
  ({EOL})+           { return TokenType.WHITE_SPACE; }
  ({WHITE_SPACE})+   { return TokenType.WHITE_SPACE; }
  {COMMENT}          { return BcfgTypes.COMMENT; }
  "["                { yybegin(IN_SECTION_HEADER); return BcfgTypes.L_BRACKET; }
  "<file:"           { yybegin(IN_FILE_PATH); return BcfgTypes.REQUIRED_INLINE; }
  "<?file:"          { yybegin(IN_FILE_PATH); return BcfgTypes.OPTIONAL_INLINE; }
  {PROPERTY_NAME}    { return BcfgTypes.PROPERTY_NAME; }
  {ASSIGN}({WHITE_SPACE})* { yybegin(IN_PROPERTY_VALUE); return BcfgTypes.ASSIGN; }
  [^]                { return TokenType.BAD_CHARACTER; }
}

// Inside "[section_name]"
<IN_SECTION_HEADER> {
  ({WHITE_SPACE})+   { return TokenType.WHITE_SPACE; }
  {SECTION_NAME}     { return BcfgTypes.SECTION_NAME; }
  "]"                { yybegin(YYINITIAL); return BcfgTypes.R_BRACKET; }
  [^]                { return TokenType.BAD_CHARACTER; }
}

// Collecting multiple values for "property = ..."
<IN_PROPERTY_VALUE> {
  {EOL}                            { yybegin(YYINITIAL); return TokenType.WHITE_SPACE; }
  {LINE_CONTINUATION_PLUS_INDENT}  { return TokenType.WHITE_SPACE; }
  {PROPERTY_VALUE_FRAGMENT}        { return BcfgTypes.PROPERTY_VALUE_FRAGMENT; }
  [^]                              { return TokenType.BAD_CHARACTER; }
}

// Inside "<file:...>" or "<?file:...>"
<IN_FILE_PATH> {
  // No whitespace or comments allowed inside
  [^>\r\n]+          { return BcfgTypes.FILE_PATH; }
  ">"                { yybegin(YYINITIAL); return BcfgTypes.END_INLINE; }
  [^]                { return TokenType.BAD_CHARACTER; }
}
