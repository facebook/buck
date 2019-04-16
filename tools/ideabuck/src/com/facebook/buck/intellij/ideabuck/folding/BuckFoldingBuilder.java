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

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckDictMaker;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionListOrComprehension;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionDefinition;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckParameterList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSuite;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.google.common.collect.Iterables;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.CustomFoldingBuilder;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Collection;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Folds function calls, function definitions, dicts, lists, and tuples. */
public class BuckFoldingBuilder extends CustomFoldingBuilder {

  @Override
  public void buildLanguageFoldRegions(
      @NotNull List<FoldingDescriptor> descriptors,
      @NotNull PsiElement root,
      @NotNull Document document,
      boolean quick) {

    foldLoadCalls(root, PsiTreeUtil.findChildrenOfType(root, BuckLoadCall.class), descriptors);

    PsiTreeUtil.findChildrenOfType(root, BuckFunctionDefinition.class)
        .forEach(e -> foldFunctionDefinition(e, descriptors));

    PsiTreeUtil.findChildrenOfType(root, BuckFunctionTrailer.class)
        .forEach(e -> foldFunctionTrailer(e, descriptors));

    PsiTreeUtil.findChildrenOfType(root, BuckDictMaker.class)
        .forEach(e -> foldDictMaker(e, descriptors));

    PsiTreeUtil.findChildrenOfType(root, BuckExpressionListOrComprehension.class)
        .forEach(e -> foldExpressionListOrComprehension(e, descriptors));
  }

  private static void foldLoadCalls(
      PsiElement root, Collection<BuckLoadCall> loadCalls, List<FoldingDescriptor> descriptors) {
    BuckLoadCall first = Iterables.getFirst(loadCalls, null);
    if (first == null) {
      return;
    }
    BuckLoadCall last = Iterables.getLast(loadCalls);
    TextRange textRange = rangeExcludingWhitespace(first, last);
    if (!textRange.isEmpty()) {
      FoldingDescriptor descriptor =
          new FoldingDescriptor(root.getNode(), textRange) {
            @Nullable
            @Override
            public String getPlaceholderText() {
              return "load ...";
            }
          };
      descriptors.add(descriptor);
    }
  }

  private static void foldFunctionDefinition(
      BuckFunctionDefinition functionDefinition, List<FoldingDescriptor> descriptors) {
    foldParameterList(functionDefinition.getParameterList(), descriptors);
    foldSuite(functionDefinition.getSuite(), descriptors);
  }

  private static void foldParameterList(
      BuckParameterList parameterList, List<FoldingDescriptor> descriptors) {
    TextRange textRange = rangeIncludingWhitespace(parameterList, parameterList);
    if (!textRange.isEmpty()) {
      FoldingDescriptor descriptor = new FoldingDescriptor(parameterList.getNode(), textRange);
      descriptors.add(descriptor);
    }
  }

  private static void foldSuite(BuckSuite suite, List<FoldingDescriptor> descriptors) {
    TextRange textRange = rangeExcludingWhitespace(suite, suite);
    if (!textRange.isEmpty()) {
      FoldingDescriptor descriptor = new FoldingDescriptor(suite.getNode(), textRange);
      descriptors.add(descriptor);
    }
  }

  private static void foldFunctionTrailer(
      BuckFunctionTrailer functionTrailer, List<FoldingDescriptor> descriptors) {
    TextRange textRange = rangeExcludingWhitespace(functionTrailer, functionTrailer);
    if (!textRange.isEmpty()) {
      FoldingDescriptor descriptor =
          new FoldingDescriptor(functionTrailer.getNode(), textRange) {
            @Nullable
            @Override
            public String getPlaceholderText() {
              List<BuckArgument> argumentList = functionTrailer.getArgumentList();
              if (argumentList.size() > 1) {
                for (BuckArgument argument : argumentList) {
                  BuckIdentifier identifier = argument.getIdentifier();
                  if (identifier != null && identifier.getText().equals("name")) {
                    return "(" + argument.getText() + ", ...)";
                  }
                }
              }
              return "(...)";
            }
          };
      descriptors.add(descriptor);
    }
  }

  private static void foldDictMaker(BuckDictMaker dictMaker, List<FoldingDescriptor> descriptors) {
    if (dictMaker.getDictEntryList().size() < 5) {
      return;
    }
    TextRange textRange = rangeIncludingWhitespace(dictMaker, dictMaker);
    if (!textRange.isEmpty()) {
      FoldingDescriptor descriptor =
          new FoldingDescriptor(dictMaker.getParent().getNode(), textRange);
      descriptors.add(descriptor);
    }
  }

  private static void foldExpressionListOrComprehension(
      BuckExpressionListOrComprehension expressionListOrComprehension,
      List<FoldingDescriptor> descriptors) {
    if (expressionListOrComprehension.getExpressionList().size() <= 1) {
      return;
    }
    TextRange textRange =
        rangeIncludingWhitespace(expressionListOrComprehension, expressionListOrComprehension);
    if (!textRange.isEmpty()) {
      FoldingDescriptor descriptor =
          new FoldingDescriptor(expressionListOrComprehension.getParent().getNode(), textRange);
      descriptors.add(descriptor);
    }
  }

  @Nullable
  @Override
  public String getLanguagePlaceholderText(@NotNull ASTNode astNode, @NotNull TextRange range) {
    return null;
  }

  @Override
  protected boolean isRegionCollapsedByDefault(@NotNull ASTNode node) {
    return false;
  }

  // Helper methods...consider moving these to a utility class?

  private static @Nullable PsiElement previousInTree(PsiElement element) {
    PsiElement sibling = element.getPrevSibling();
    if (sibling != null) {
      return deepLast(sibling);
    }
    PsiElement parent = element.getParent();
    if (parent != null) {
      return previousInTree(parent);
    }
    return null;
  }

  private static @Nullable PsiElement nextInTree(PsiElement element) {
    PsiElement sibling = element.getNextSibling();
    if (sibling != null) {
      return deepFirst(sibling);
    }
    PsiElement parent = element.getParent();
    if (parent != null) {
      return nextInTree(parent);
    }
    return null;
  }

  private static PsiElement deepFirst(PsiElement element) {
    while (true) {
      PsiElement child = element.getFirstChild();
      if (child == null) {
        break;
      }
      element = child;
    }
    return element;
  }

  private static PsiElement deepLast(PsiElement element) {
    while (true) {
      PsiElement child = element.getLastChild();
      if (child == null) {
        break;
      }
      element = child;
    }
    return element;
  }

  private static final TokenSet WHITESPACE_TOKENS =
      TokenSet.create(TokenType.WHITE_SPACE, BuckTypes.INDENT, BuckTypes.DEDENT);

  /** Returns a text range including the first/last elements *and* adjacent whitespace. */
  private static TextRange rangeIncludingWhitespace(PsiElement first, PsiElement last) {
    first = deepFirst(first);
    last = deepLast(last);
    while (true) {
      PsiElement previous = previousInTree(first);
      if (previous == null || !WHITESPACE_TOKENS.contains(previous.getNode().getElementType())) {
        break;
      }
      first = previous;
    }
    while (true) {
      PsiElement next = nextInTree(last);
      if (next == null || !WHITESPACE_TOKENS.contains(next.getNode().getElementType())) {
        break;
      }
      last = next;
    }
    return new TextRange(first.getTextRange().getStartOffset(), last.getTextRange().getEndOffset());
  }

  /** Returns a text range from the first to the last elements, excluding whitespace. */
  private static TextRange rangeExcludingWhitespace(PsiElement first, PsiElement last) {
    first = deepFirst(first);
    last = deepLast(last);
    while (WHITESPACE_TOKENS.contains(first.getNode().getElementType())) {
      PsiElement next = nextInTree(first);
      if (next == null) {
        break;
      }
      first = next;
    }
    while (WHITESPACE_TOKENS.contains(last.getNode().getElementType())) {
      PsiElement previous = previousInTree(last);
      if (previous == null) {
        break;
      }
      last = previous;
    }
    return new TextRange(first.getTextRange().getStartOffset(), last.getTextRange().getEndOffset());
  }
}
