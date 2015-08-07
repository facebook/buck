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

import com.facebook.buck.intellij.plugin.lang.psi.BuckTypes;
import com.intellij.lang.ASTNode;
import com.intellij.lang.Language;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.FlexAdapter;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;

import java.io.Reader;

public class BuckParserDefinition implements ParserDefinition {

  public static final TokenSet WHITE_SPACES = TokenSet.create(TokenType.WHITE_SPACE);
  public static final TokenSet COMMENTS = TokenSet.create(BuckTypes.COMMENT);

  public static final IFileElementType FILE = new IFileElementType(
      Language.<BuckLanguage>findInstance(BuckLanguage.class));

  @Override
  public Lexer createLexer(Project project) {
    return new FlexAdapter(new _BuckLexer((Reader) null));
  }

  public TokenSet getWhitespaceTokens() {
    return WHITE_SPACES;
  }

  public TokenSet getCommentTokens() {
    return COMMENTS;
  }

  public TokenSet getStringLiteralElements() {
    return TokenSet.EMPTY;
  }

  public PsiParser createParser(final Project project) {
    return new BuckParser();
  }

  @Override
  public IFileElementType getFileNodeType() {
    return FILE;
  }

  public PsiFile createFile(FileViewProvider viewProvider) {
    return new BuckFile(viewProvider);
  }

  public SpaceRequirements spaceExistanceTypeBetweenTokens(ASTNode left, ASTNode right) {
    return SpaceRequirements.MAY;
  }

  public PsiElement createElement(ASTNode node) {
    return BuckTypes.Factory.createElement(node);
  }
}
