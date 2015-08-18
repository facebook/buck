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

package com.facebook.buck.intellij.plugin.format;

import com.facebook.buck.intellij.plugin.lang.psi.BuckTypes;
import com.intellij.lang.BracePair;
import com.intellij.lang.PairedBraceMatcher;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;

public class BuckBraceMatcher implements PairedBraceMatcher {

  private static final BracePair[] PAIRS = {
      new BracePair(BuckTypes.L_BRACKET, BuckTypes.R_BRACKET, true),
      new BracePair(BuckTypes.L_PARENTHESES, BuckTypes.R_PARENTHESES, true),
      new BracePair(BuckTypes.L_CURLY, BuckTypes.R_CURLY, true),
  };

  @Override
  public BracePair[] getPairs() {
    return PAIRS;
  }

  @Override
  public boolean isPairedBracesAllowedBeforeType(
      IElementType lbraceType, IElementType contextType) {
    return true;
  }

  @Override
  public int getCodeConstructStart(PsiFile psiFile, int openingBraceOffset) {
    return openingBraceOffset;
  }
}
