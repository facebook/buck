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
package com.facebook.buck.intellij.ideabuck.highlight;

import static com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey;

import com.facebook.buck.intellij.ideabuck.lang.BcfgLexerAdapter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTypes;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.HighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import org.jetbrains.annotations.NotNull;

/** Syntax highlighting for {@code .buckconfig} files. */
public class BcfgSyntaxHighlighter extends SyntaxHighlighterBase {
  public static final TextAttributesKey PUNCTUATION =
      createTextAttributesKey(
          "BUCKCONFIG.PUNCTUATION", DefaultLanguageHighlighterColors.OPERATION_SIGN);
  public static final TextAttributesKey SECTION =
      createTextAttributesKey("BUCKCONFIG.SECTION", DefaultLanguageHighlighterColors.LABEL);
  public static final TextAttributesKey PROPERTY =
      createTextAttributesKey("BUCKCONFIG.PROPERTY", DefaultLanguageHighlighterColors.CONSTANT);
  public static final TextAttributesKey VALUE =
      createTextAttributesKey("BUCKCONFIG.VALUE", DefaultLanguageHighlighterColors.STRING);
  public static final TextAttributesKey COMMENT =
      createTextAttributesKey("BUCKCONFIG.COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT);
  public static final TextAttributesKey FILE_PATH =
      createTextAttributesKey("BUCKCONFIG.FILE_PATH", DefaultLanguageHighlighterColors.CONSTANT);
  public static final TextAttributesKey BAD_CHARACTER =
      createTextAttributesKey("BUCKCONFIG.BAD_CHARACTER", HighlighterColors.BAD_CHARACTER);

  private static final TextAttributesKey[] BAD_CHAR_KEYS = new TextAttributesKey[] {BAD_CHARACTER};
  private static final TextAttributesKey[] PUNCTUATION_KEYS = new TextAttributesKey[] {PUNCTUATION};
  private static final TextAttributesKey[] SECTION_KEYS = new TextAttributesKey[] {SECTION};
  private static final TextAttributesKey[] PROPERTY_KEYS = new TextAttributesKey[] {PROPERTY};
  private static final TextAttributesKey[] VALUE_KEYS = new TextAttributesKey[] {VALUE};
  private static final TextAttributesKey[] COMMENT_KEYS = new TextAttributesKey[] {COMMENT};
  private static final TextAttributesKey[] FILE_PATH_KEYS = new TextAttributesKey[] {FILE_PATH};
  private static final TextAttributesKey[] EMPTY_KEYS = new TextAttributesKey[0];

  @NotNull
  @Override
  public Lexer getHighlightingLexer() {
    return new BcfgLexerAdapter();
  }

  @NotNull
  @Override
  public TextAttributesKey[] getTokenHighlights(IElementType tokenType) {
    if (tokenType.equals(BcfgTypes.COMMENT)) {
      return COMMENT_KEYS;
    } else if (tokenType.equals(BcfgTypes.L_BRACKET)
        || tokenType.equals(BcfgTypes.R_BRACKET)
        || tokenType.equals(BcfgTypes.REQUIRED_INLINE)
        || tokenType.equals(BcfgTypes.OPTIONAL_INLINE)
        || tokenType.equals(BcfgTypes.END_INLINE)
        || tokenType.equals(BcfgTypes.ASSIGN)) {
      return PUNCTUATION_KEYS;
    } else if (tokenType.equals(BcfgTypes.PROPERTY_VALUE_FRAGMENT)) {
      return VALUE_KEYS;
    } else if (tokenType.equals(BcfgTypes.SECTION_NAME)) {
      return SECTION_KEYS;
    } else if (tokenType.equals(BcfgTypes.PROPERTY_NAME)) {
      return PROPERTY_KEYS;
    } else if (tokenType.equals(BcfgTypes.FILE_PATH)) {
      return FILE_PATH_KEYS;
    } else if (tokenType.equals(TokenType.BAD_CHARACTER)) {
      return BAD_CHAR_KEYS;
    } else {
      return EMPTY_KEYS;
    }
  }
}
