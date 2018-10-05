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

package com.facebook.buck.intellij.ideabuck.highlight;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

import com.facebook.buck.intellij.ideabuck.lang.BuckLexerAdapter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import java.util.HashMap;
import java.util.Map;

/** Syntax highlighter for buck PSI elements. */
public class BuckSyntaxHighlighter extends SyntaxHighlighterBase {
  private static final Map<IElementType, TextAttributesKey> sKeys = new HashMap<>();

  public static final TextAttributesKey BUCK_KEYWORD =
      createTextAttributesKey("BUCK.KEYWORD", DefaultLanguageHighlighterColors.KEYWORD);
  public static final TextAttributesKey BUCK_NUMBER =
      createTextAttributesKey("BUCK.NUMBER", DefaultLanguageHighlighterColors.NUMBER);
  public static final TextAttributesKey BUCK_COMMENT =
      createTextAttributesKey("BUCK.COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
  public static final TextAttributesKey BUCK_RULE_NAME =
      createTextAttributesKey(
          "BUCK.RULE_NAME", DefaultLanguageHighlighterColors.FUNCTION_DECLARATION);
  public static final TextAttributesKey BUCK_GLOB =
      createTextAttributesKey("BUCK.GLOB", DefaultLanguageHighlighterColors.FUNCTION_CALL);
  public static final TextAttributesKey BUCK_FUNCTION_NAME =
      createTextAttributesKey("BUCK.FUNCTION_NAME", DefaultLanguageHighlighterColors.FUNCTION_CALL);
  public static final TextAttributesKey BUCK_STRING =
      createTextAttributesKey("BUCK.STRING", DefaultLanguageHighlighterColors.STRING);
  public static final TextAttributesKey BUCK_DOC_STRING =
      createTextAttributesKey("BUCK.DOC_STRING", DefaultLanguageHighlighterColors.DOC_COMMENT);
  public static final TextAttributesKey BUCK_PROPERTY_LVALUE =
      createTextAttributesKey("BUCK.PROPERTY_LVALUE", DefaultLanguageHighlighterColors.PARAMETER);
  public static final TextAttributesKey BUCK_TARGET =
      createTextAttributesKey("BUCK.TARGET", DefaultLanguageHighlighterColors.STRING);
  public static final TextAttributesKey BUCK_INVALID_TARGET =
      createTextAttributesKey("BUCK.INVALID_TARGET", DefaultLanguageHighlighterColors.STRING);
  public static final TextAttributesKey BUCK_IDENTIFIER =
      createTextAttributesKey("BUCK.IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER);
  public static final TextAttributesKey BUCK_FILE_NAME =
      createTextAttributesKey("BUCK.FILE_NAME", DefaultLanguageHighlighterColors.IDENTIFIER);

  static {
    for (IElementType type :
        new IElementType[] {
          BuckTypes.DEF,
          BuckTypes.FOR,
          BuckTypes.IN,
          BuckTypes.AND,
          BuckTypes.OR,
          BuckTypes.NOT,
          BuckTypes.IF,
          BuckTypes.ELSE,
          BuckTypes.ELIF,
          BuckTypes.BREAK,
          BuckTypes.CONTINUE,
          BuckTypes.PASS,
          BuckTypes.RETURN,
          BuckTypes.LAMBDA,
          BuckTypes.BOOLEAN,
          BuckTypes.NONE
        }) {
      sKeys.put(type, BUCK_KEYWORD);
    }
    sKeys.put(BuckTypes.SINGLE_QUOTED_DOC_STRING, BUCK_STRING);
    sKeys.put(BuckTypes.DOUBLE_QUOTED_DOC_STRING, BUCK_STRING);
    sKeys.put(BuckTypes.GLOB, BUCK_GLOB);
    sKeys.put(BuckTypes.DOUBLE_QUOTED_STRING, BUCK_STRING);
    sKeys.put(BuckTypes.SINGLE_QUOTED_STRING, BUCK_STRING);
    sKeys.put(BuckTypes.NUMBER, BUCK_NUMBER);
    sKeys.put(BuckTypes.LINE_COMMENT, BUCK_COMMENT);
    sKeys.put(BuckTypes.FUNCTION_NAME, BUCK_FUNCTION_NAME);
    sKeys.put(BuckTypes.PROPERTY_LVALUE, BUCK_PROPERTY_LVALUE);
    sKeys.put(BuckTypes.IDENTIFIER, BUCK_IDENTIFIER);

    sKeys.put(TokenType.BAD_CHARACTER, HighlighterColors.BAD_CHARACTER);
  }

  @Override
  public Lexer getHighlightingLexer() {
    return new BuckLexerAdapter();
  }

  @Override
  public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
    return pack(sKeys.getOrDefault(tokenType, null));
  }
}
