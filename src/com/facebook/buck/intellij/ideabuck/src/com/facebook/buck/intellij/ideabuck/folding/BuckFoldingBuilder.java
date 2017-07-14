/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.folding;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckValueArrayImpl;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** A first pass at folding support for BUCK files - folds arrays but not rules */
public class BuckFoldingBuilder extends FoldingBuilderEx {

  /** We fold large arrays by default; this constant defines "large" */
  private static final int DEFAULT_FOLDING_SIZE = 6;

  private final TokenSet arrayElements = TokenSet.create(BuckTypes.ARRAY_ELEMENTS);
  private final TokenSet values = TokenSet.create(BuckTypes.VALUE);

  @NotNull
  @Override
  public FoldingDescriptor[] buildFoldRegions(
      @NotNull PsiElement root, @NotNull Document document, boolean quick) {
    List<FoldingDescriptor> descriptors = new ArrayList<>();

    Collection<BuckValueArrayImpl> arrays =
        PsiTreeUtil.findChildrenOfType(root, BuckValueArrayImpl.class);
    for (final BuckValueArrayImpl array : arrays) {
      descriptors.add(
          new FoldingDescriptor(
              array.getNode(),
              new TextRange(
                  array.getTextRange().getStartOffset() + 1,
                  array.getTextRange().getEndOffset() - 1)));
    }
    return descriptors.toArray(new FoldingDescriptor[descriptors.size()]);
  }

  @Nullable
  @Override
  public String getPlaceholderText(@NotNull ASTNode astNode) {
    if (!(astNode instanceof CompositeElement)) {
      return null;
    }
    int size = countValues((CompositeElement) astNode);
    // Return null (the default value) if countValues() returns an error code
    return size < 0 ? null : Integer.toString(size);
  }

  @Override
  public boolean isCollapsedByDefault(@NotNull ASTNode astNode) {
    if (!(astNode instanceof CompositeElement)) {
      return false;
    }
    CompositeElement compositeElement = (CompositeElement) astNode;
    // The debugger suggests that we can use reference equality, here, but .equals() seems safer
    return BuckTypes.VALUE_ARRAY.equals(compositeElement.getElementType())
        && countValues(compositeElement) >= DEFAULT_FOLDING_SIZE;
  }

  private int countValues(CompositeElement compositeElement) {
    ASTNode[] children = compositeElement.getChildren(arrayElements);
    if (children == null || children.length != 1) {
      return -1;
    }
    CompositeElement element = (CompositeElement) children[0];
    return element.countChildren(values);
  }
}
