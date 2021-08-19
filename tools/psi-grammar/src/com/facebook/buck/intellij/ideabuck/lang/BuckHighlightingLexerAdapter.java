/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.lang;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTokenType;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.intellij.psi.tree.IElementType;

/**
 * Start from IJ 202, the IntelliJ Platform will check if the lexer advances or not by looking at
 * the offset & token type after calling {@link #advance()}. However, to parse starlark correctly,
 * we will need to return 1:1 pair of {@link BuckTypes#INDENT} and {@link BuckTypes#DEDENT} and thus
 * there could be multiple {@link BuckTypes#DEDENT}s at the exact same offset if the previous
 * statement is indented multiple times, which upsets {@link
 * com.intellij.openapi.editor.ex.util.ValidatingLexerWrapper#advance}.
 *
 * <p>Luckily, the check is only required for syntax highlighting so we create a subclass of {@link
 * BuckLexerAdapter}, which will only be used by {@link
 * com.facebook.buck.intellij.ideabuck.highlight.BuckSyntaxHighlighter}. This class returns a {@link
 * BuckHighlightingLexerAdapter#DEDENT_ALT} to replace {@link BuckTypes#DEDENT} if we have already
 * returned a {@link BuckTypes#DEDENT} previously.
 */
public class BuckHighlightingLexerAdapter extends BuckLexerAdapter {
  private static final IElementType DEDENT_ALT = new BuckTokenType("DEDENT_ALT");
  private IElementType prevTokenType = null;

  @Override
  public IElementType getTokenType() {
    IElementType tokenType = super.getTokenType();
    if (prevTokenType == BuckTypes.DEDENT && tokenType == BuckTypes.DEDENT) {
      prevTokenType = DEDENT_ALT;
    } else {
      prevTokenType = tokenType;
    }
    return prevTokenType;
  }
}
