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
LINE_WHITESPACE = [\ \t\f]+
LINE_CONTINUATION = \\(\r|\n|\r\n)
LINE_CONTINUATION_PLUS_INDENT = {LINE_CONTINUATION}{LINE_WHITESPACE}*
COMMENT = [#;][^\r\n]*

L_BRACKET = \[
R_BRACKET = \]
ASSIGN = [:=]
REQUIRED_FILE = "<file:"
OPTIONAL_FILE = "<?file:"

// Section and property names strip leading/trailing whitespace, so we
// will reconstruct the name from the non-whitespace fragments
SECTION_NAME_FRAGMENT   = ([^\\\s\r\n\]]|\\[^\r\n])+
PROPERTY_NAME_FRAGMENT  = ([^\\\s\r\n\]<:=]|\\[^\r\n])+
// Property values cannot have leading whitespace, but they can have trailing whitespace
PROPERTY_VALUE_FRAGMENT = ([^\\\s\r\n]|\\[^\r\n]) ([^\\\r\n]|\\[^\r\n])*

%state IN_SECTION_HEADER
%state IN_PROPERTY_NAME
%state IN_PROPERTY_VALUE
%state IN_FILE_PATH

%%

<YYINITIAL> {
  {EOL}                     { return TokenType.WHITE_SPACE; }
  {LINE_WHITESPACE}         { return TokenType.WHITE_SPACE; }
  {LINE_CONTINUATION}       { return TokenType.WHITE_SPACE; }
  {COMMENT}                 { return BcfgTypes.COMMENT; }
  "["                       { yybegin(IN_SECTION_HEADER); return BcfgTypes.L_BRACKET; }
  "<file:"                  { yybegin(IN_FILE_PATH); return BcfgTypes.REQUIRED_FILE; }
  "<?file:"                 { yybegin(IN_FILE_PATH); return BcfgTypes.OPTIONAL_FILE; }
  [^]                       { yybegin(IN_PROPERTY_NAME); }
  <<EOF>>                   { return null; }
}

// Inside "[section_name]"
<IN_SECTION_HEADER> {
  {LINE_WHITESPACE}                { return TokenType.WHITE_SPACE; }
  {LINE_CONTINUATION_PLUS_INDENT}  { return TokenType.WHITE_SPACE; }
  {SECTION_NAME_FRAGMENT}          { return BcfgTypes.SECTION_NAME_FRAGMENT; }
  "]"                              { yybegin(YYINITIAL); return BcfgTypes.R_BRACKET; }
  [^]                              { return TokenType.BAD_CHARACTER; }
}

// Collecting multiple parts of property name before the '='
<IN_PROPERTY_NAME> {
  {LINE_WHITESPACE}                { return TokenType.WHITE_SPACE; } // significant
  {LINE_CONTINUATION_PLUS_INDENT}  { return TokenType.WHITE_SPACE; }
  {ASSIGN}                         { yybegin(IN_PROPERTY_VALUE); return BcfgTypes.ASSIGN; }
  {PROPERTY_NAME_FRAGMENT}         { return BcfgTypes.PROPERTY_NAME_FRAGMENT; }
  [^]                              { return TokenType.BAD_CHARACTER; }
}

// Collecting multiple values for "property = ..."
<IN_PROPERTY_VALUE> {
  {EOL}                            { yypushback(1); yybegin(YYINITIAL); return BcfgTypes.PROPERTY_END; }
  {LINE_WHITESPACE}                { return TokenType.WHITE_SPACE; }
  {LINE_CONTINUATION_PLUS_INDENT}  { return TokenType.WHITE_SPACE; }
  {PROPERTY_VALUE_FRAGMENT}        { return BcfgTypes.PROPERTY_VALUE_FRAGMENT; }
  <<EOF>>                          { yybegin(YYINITIAL); return BcfgTypes.PROPERTY_END; }
  [^]                              { return TokenType.BAD_CHARACTER; }
}

// Inside "<file:...>" or "<?file:...>"
<IN_FILE_PATH> {
  // No whitespace or comments allowed inside
  [^>\r\n]+          { return BcfgTypes.FILE_PATH; }
  ">"                { yybegin(YYINITIAL); return BcfgTypes.END_INLINE; }
  [^]                { return TokenType.BAD_CHARACTER; }
}
