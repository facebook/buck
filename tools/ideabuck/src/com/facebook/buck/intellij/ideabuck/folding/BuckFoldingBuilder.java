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

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgumentList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionListOrComprehension;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPropertyLvalue;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.impl.source.tree.CompositeElement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Folds rules and arrays */
public class BuckFoldingBuilder extends FoldingBuilderEx {

  @NotNull
  @Override
  public FoldingDescriptor[] buildFoldRegions(
      @NotNull PsiElement root, @NotNull Document document, boolean quick) {
    List<FoldingDescriptor> descriptors = new ArrayList<>();

    PsiTreeUtil.findChildrenOfAnyType(
            root,
            BuckExpressionListOrComprehension.class,
            BuckExpressionList.class,
            BuckArgumentList.class)
        .forEach(
            element -> {
              TextRange elementTextRange = element.getTextRange();
              if (elementTextRange.getLength() > 0) {
                descriptors.add(new FoldingDescriptor(element.getNode(), elementTextRange));
              }
            });

    return descriptors.toArray(new FoldingDescriptor[0]);
  }

  @Nullable
  @Override
  public String getPlaceholderText(@NotNull ASTNode astNode) {
    if (!(astNode instanceof CompositeElement)) {
      return null;
    }

    CompositeElement compositeElement = (CompositeElement) astNode;
    IElementType type = compositeElement.getElementType();

    if (type.equals(BuckTypes.EXPRESSION_LIST_OR_COMPREHENSION)) {
      return getArrayPlaceholderText(compositeElement, BuckTypes.EXPRESSION);
    } else if (type.equals(BuckTypes.EXPRESSION_LIST)) {
      return getArrayPlaceholderText(compositeElement, BuckTypes.EXPRESSION);
    } else if (type.equals(BuckTypes.ARGUMENT_LIST)) {
      return getRulePlaceholderText(compositeElement);
    } else {
      return null;
    }
  }

  private String getArrayPlaceholderText(
      CompositeElement compositeElement, IElementType elementType) {
    int size = compositeElement.countChildren(TokenSet.create(elementType));
    // Return null (the default value) if countValues() returns an error code
    return size < 2 ? null : String.format("%d entries", size);
  }

  @Nullable
  private String getRulePlaceholderText(CompositeElement compositeElement) {
    PsiElement psiElement = compositeElement.getPsi();
    String name = null;
    Collection<BuckPropertyLvalue> lvalues =
        PsiTreeUtil.findChildrenOfType(psiElement, BuckPropertyLvalue.class);
    for (BuckPropertyLvalue lvalue : lvalues) {
      if (lvalue.getText().equals("name")) {
        PsiElement element = lvalue;
        while (element != null) {
          element = element.getNextSibling();
          if (element instanceof BuckExpression) {
            name = BuckPsiUtils.getStringValueFromExpression((BuckExpression) element);
            break;
          }
        }
        break;
      }
    }
    return name;
  }

  @Override
  public boolean isCollapsedByDefault(@NotNull ASTNode astNode) {
    return false;
  }
}
