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

package com.facebook.buck.intellij.ideabuck.util;

import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckAndExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArithmeticExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckAtomicExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckBitwiseAndExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckComparisonExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckCompoundStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckComprehensionFor;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionListOrComprehension;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFactorExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckForStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionDefinition;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIfStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckNotExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckOrExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckParameter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckParameterList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPowerExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckShiftExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSimpleExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSimpleExpressionList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSimpleStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSmallStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSuite;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTermExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckXorExpression;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

public final class BuckPsiUtils {

  private static final Logger LOG = Logger.getInstance(BuckPsiUtils.class);

  public static final TokenSet STRING_LITERALS =
      TokenSet.create(
          BuckTypes.APOSTROPHED_STRING,
          BuckTypes.APOSTROPHED_RAW_STRING,
          BuckTypes.TRIPLE_APOSTROPHED_STRING,
          BuckTypes.TRIPLE_APOSTROPHED_RAW_STRING,
          BuckTypes.QUOTED_STRING,
          BuckTypes.QUOTED_RAW_STRING,
          BuckTypes.TRIPLE_QUOTED_STRING,
          BuckTypes.TRIPLE_QUOTED_RAW_STRING);

  private BuckPsiUtils() {}

  /**
   * Check that element type of the given AST node belongs to the token set.
   *
   * <p>It slightly less verbose than {@code set.contains(node.getElementType())} and overloaded
   * methods with the same name allow check ASTNode/PsiElement against both concrete element types
   * and token sets in uniform way.
   */
  public static boolean hasElementType(ASTNode node, TokenSet set) {
    return set.contains(node.getElementType());
  }

  /** @see #hasElementType(ASTNode, TokenSet) */
  public static boolean hasElementType(ASTNode node, IElementType... types) {
    return hasElementType(node, TokenSet.create(types));
  }

  /** @see #hasElementType(ASTNode, TokenSet) */
  public static boolean hasElementType(PsiElement element, TokenSet set) {
    return element.getNode() != null && hasElementType(element.getNode(), set);
  }

  /** @see #hasElementType(ASTNode, IElementType...) */
  public static boolean hasElementType(PsiElement element, IElementType... types) {
    return element.getNode() != null && hasElementType(element.getNode(), types);
  }

  /** Test the text value of a PSI element. */
  public static boolean testType(PsiElement element, IElementType type) {
    return element.getNode() != null && element.getNode().getElementType() == type;
  }

  /** Find the ancestor element with a specific type */
  public static PsiElement findAncestorWithType(PsiElement element, IElementType type) {
    PsiElement parent = element.getParent();
    while (parent != null) {
      if (parent.getNode() != null && parent.getNode().getElementType() == type) {
        return parent;
      }
      parent = parent.getParent();
    }
    return null;
  }

  /** Find the first child with a specific type */
  public static PsiElement findChildWithType(PsiElement element, IElementType type) {
    PsiElement[] children = element.getChildren();
    for (PsiElement child : children) {
      if (child.getNode().getElementType() == type) {
        return child;
      }
    }
    return null;
  }

  /** Searches for text in the given element, returning a {@link TextRange} if the text is found. */
  public static Optional<TextRange> findTextInElement(PsiElement element, String text) {
    return Optional.of(element)
        .map(PsiElement::getText)
        .map(s -> s.indexOf(text))
        .filter(i -> i >= 0)
        .map(
            index -> {
              int elementStart = element.getTextOffset();
              int length = text.length();
              return new TextRange(elementStart + index, elementStart + index + length);
            });
  }

  /**
   * Return the text content if the given BuckExpression has only one string value. Return null if
   * this expression has multiple values, for example: "a" + "b"
   */
  @Nullable
  public static String getStringValueFromExpression(BuckExpression expression) {
    return Optional.of(expression)
        .map(BuckExpression::getOrExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckOrExpression::getAndExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckAndExpression::getNotExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckNotExpression::getComparisonExpression)
        .map(BuckComparisonExpression::getSimpleExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckPsiUtils::getStringValueFromSimpleExpression)
        .orElse(null);
  }
  /**
   * Return the text content if the given BuckSimpleExpression has only one string value. Return
   * null if this expression has multiple values, for example: "a" + "b"
   */
  @Nullable
  public static String getStringValueFromSimpleExpression(BuckSimpleExpression expression) {
    return Optional.of(expression)
        .map(BuckSimpleExpression::getXorExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckXorExpression::getBitwiseAndExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckBitwiseAndExpression::getShiftExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckShiftExpression::getArithmeticExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckArithmeticExpression::getTermExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckTermExpression::getFactorExpressionList)
        .filter(list -> list.size() == 1)
        .map(list -> list.get(0))
        .map(BuckFactorExpression::getPowerExpression)
        .filter(e -> e.getExpressionTrailerList().isEmpty())
        .filter(e -> e.getFactorExpression() == null)
        .map(BuckPowerExpression::getAtomicExpression)
        .map(BuckAtomicExpression::getString)
        .map(BuckString::getValue)
        .orElse(null);
  }

  /**
   * Returns the text content of the given element (without the appropriate quoting).
   *
   * @deprecated Use the variation of this method that accepts a {@link BuckString}.
   */
  @Deprecated
  @Nullable
  public static String getStringValueFromBuckString(@Nullable PsiElement stringElement) {
    if (stringElement == null) {
      return null;
    }
    if (hasElementType(stringElement, STRING_LITERALS)) {
      stringElement = stringElement.getParent();
    }
    if (stringElement instanceof BuckString) {
      return ((BuckString) stringElement).getValue();
    }
    return null;
  }

  /**
   * Returns the text content of the given string (without the appropriate quoting).
   *
   * <p>Note that this method is currently underdeveloped and hacky. It does not process escape
   * sequences correctly.
   */
  public static String getStringValueFromBuckString(BuckString buckString) {
    return buckString.getValue();
  }

  /**
   * Returns the definition for a rule with the given target name in the given root, or {@code null}
   * if it cannot be found.
   */
  @Nullable
  public static BuckFunctionTrailer findTargetInPsiTree(PsiElement root, String name) {
    for (BuckFunctionTrailer buckRuleBody :
        PsiTreeUtil.findChildrenOfType(root, BuckFunctionTrailer.class)) {
      for (BuckArgument buckArgument :
          PsiTreeUtil.findChildrenOfType(buckRuleBody, BuckArgument.class)) {
        if (!Optional.ofNullable(buckArgument.getIdentifier())
            .map(BuckIdentifier::getIdentifierToken)
            .map(PsiElement::getText)
            .filter("name"::equals)
            .isPresent()) {
          continue;
        }
        if (name.equals(getStringValueFromExpression(buckArgument.getExpression()))) {
          return buckRuleBody;
        }
      }
    }
    return null;
  }

  /**
   * Returns a mapping from rule names that start with the given prefix to their target elements.
   */
  public static Map<String, PsiElement> findTargetsInPsiTree(PsiFile psiFile, String namePrefix) {
    Map<String, PsiElement> targetsByName = new HashMap<>();
    for (BuckFunctionTrailer buckRuleBody :
        PsiTreeUtil.findChildrenOfType(psiFile, BuckFunctionTrailer.class)) {
      for (BuckArgument buckArgument :
          PsiTreeUtil.findChildrenOfType(buckRuleBody, BuckArgument.class)) {
        BuckIdentifier buckIdentifier = buckArgument.getIdentifier();
        if (buckIdentifier == null || !"name".equals(buckIdentifier.getName())) {
          continue;
        }
        String name = BuckPsiUtils.getStringValueFromExpression(buckArgument.getExpression());
        if (name != null) {
          if (name.startsWith(namePrefix)) {
            targetsByName.put(name, buckRuleBody);
          }
          break;
        }
      }
    }
    return targetsByName;
  }

  private interface SymbolVisitor {
    void visit(String name, PsiElement element);
  }

  private static class FoundSymbol extends RuntimeException {
    public PsiElement element;

    FoundSymbol(PsiElement element) {
      this.element = element;
    }
  }

  /*
   * A bit hacky:  symbols are either top-level functions, top-level identifiers,
   * or identifiers loaded from a {@code load()} statement.
   */
  private static void visitSymbols(PsiElement psiElement, SymbolVisitor visitor) {
    if (psiElement == null) {
      return;
    }
    Consumer<PsiElement> recurse = e -> visitSymbols(e, visitor);
    if (psiElement instanceof BuckFile) {
      Stream.of(((BuckFile) psiElement).getChildren()).forEach(recurse);
    } else if (psiElement instanceof BuckIdentifier) {
      visitor.visit(((BuckIdentifier) psiElement).getName(), psiElement);
    } else if (psiElement instanceof BuckLoadCall) {
      ((BuckLoadCall) psiElement).getLoadArgumentList().forEach(recurse);
    } else if (psiElement instanceof BuckLoadArgument) {
      BuckIdentifier identifier = ((BuckLoadArgument) psiElement).getIdentifier();
      if (identifier != null) {
        recurse.accept(identifier);
      } else {
        BuckString nameElement = ((BuckLoadArgument) psiElement).getString();
        visitor.visit(nameElement.getValue(), nameElement);
      }
    } else if (psiElement instanceof BuckFunctionDefinition) {
      recurse.accept(((BuckFunctionDefinition) psiElement).getIdentifier());
    } else if (psiElement instanceof BuckParameterList) {
      ((BuckParameterList) psiElement).getParameterList().forEach(recurse::accept);
    } else if (psiElement instanceof BuckParameter) {
      recurse.accept(((BuckParameter) psiElement).getIdentifier());
    } else if (psiElement instanceof BuckStatement) {
      recurse.accept(((BuckStatement) psiElement).getSimpleStatement());
      recurse.accept(((BuckStatement) psiElement).getCompoundStatement());
    } else if (psiElement instanceof BuckIfStatement) {
      List<BuckExpression> expressionList = ((BuckIfStatement) psiElement).getExpressionList();
      expressionList.forEach(recurse);
      List<BuckSuite> suiteList = ((BuckIfStatement) psiElement).getSuiteList();
      suiteList.forEach(recurse);
    } else if (psiElement instanceof BuckSimpleStatement) {
      ((BuckSimpleStatement) psiElement).getSmallStatementList().forEach(recurse);
    } else if (psiElement instanceof BuckCompoundStatement) {
      recurse.accept(((BuckCompoundStatement) psiElement).getForStatement());
      recurse.accept(((BuckCompoundStatement) psiElement).getIfStatement());
      recurse.accept(((BuckCompoundStatement) psiElement).getFunctionDefinition());
    } else if (psiElement instanceof BuckForStatement) {
      recurse.accept(((BuckForStatement) psiElement).getSimpleExpressionList());
      recurse.accept(((BuckForStatement) psiElement).getSuite());
    } else if (psiElement instanceof BuckSmallStatement) {
      recurse.accept(((BuckSmallStatement) psiElement).getExpressionStatement());
      recurse.accept(((BuckSmallStatement) psiElement).getLoadCall());
    } else if (psiElement instanceof BuckSuite) {
      recurse.accept(((BuckSuite) psiElement).getSimpleStatement());
      ((BuckSuite) psiElement).getStatementList().forEach(recurse);
    } else if (psiElement instanceof BuckExpressionStatement) {
      List<BuckExpressionList> list =
          ((BuckExpressionStatement) psiElement).getExpressionListList();
      list.subList(0, list.size() - 1).forEach(recurse::accept);
    } else if (psiElement instanceof BuckExpressionList) {
      ((BuckExpressionList) psiElement).getExpressionList().forEach(recurse);
    } else if (psiElement instanceof BuckExpressionListOrComprehension) {
      BuckExpressionListOrComprehension beloc = ((BuckExpressionListOrComprehension) psiElement);
      if (beloc.getComprehensionFor() == null) {
        recurse.accept(((BuckExpressionListOrComprehension) psiElement).getComprehensionFor());
      }
    } else if (psiElement instanceof BuckComprehensionFor) {
      recurse.accept(((BuckComprehensionFor) psiElement).getSimpleExpressionList());
    } else if (psiElement instanceof BuckExpression) {
      recurse.accept(((BuckExpression) psiElement).getOrExpressionList().get(0));
      recurse.accept(((BuckExpression) psiElement).getExpression());
    } else if (psiElement instanceof BuckOrExpression) {
      List<BuckAndExpression> list = ((BuckOrExpression) psiElement).getAndExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckAndExpression) {
      List<BuckNotExpression> list = ((BuckAndExpression) psiElement).getNotExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckNotExpression) {
      recurse.accept(((BuckNotExpression) psiElement).getComparisonExpression());
    } else if (psiElement instanceof BuckComparisonExpression) {
      List<BuckSimpleExpression> list =
          ((BuckComparisonExpression) psiElement).getSimpleExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckSimpleExpressionList) {
      (((BuckSimpleExpressionList) psiElement).getSimpleExpressionList()).forEach(recurse);
    } else if (psiElement instanceof BuckSimpleExpression) {
      List<BuckXorExpression> list = ((BuckSimpleExpression) psiElement).getXorExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckXorExpression) {
      List<BuckBitwiseAndExpression> list =
          ((BuckXorExpression) psiElement).getBitwiseAndExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckBitwiseAndExpression) {
      List<BuckShiftExpression> list =
          ((BuckBitwiseAndExpression) psiElement).getShiftExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckShiftExpression) {
      List<BuckArithmeticExpression> list =
          ((BuckShiftExpression) psiElement).getArithmeticExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckArithmeticExpression) {
      List<BuckTermExpression> list =
          ((BuckArithmeticExpression) psiElement).getTermExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckTermExpression) {
      List<BuckFactorExpression> list = ((BuckTermExpression) psiElement).getFactorExpressionList();
      if (list.size() == 1) {
        recurse.accept(list.get(0));
      }
    } else if (psiElement instanceof BuckFactorExpression) {
      recurse.accept(((BuckFactorExpression) psiElement).getPowerExpression());
    } else if (psiElement instanceof BuckPowerExpression) {
      BuckPowerExpression powerExpression = (BuckPowerExpression) psiElement;
      if (powerExpression.getExpressionTrailerList().isEmpty()
          && powerExpression.getFactorExpression() == null) {
        recurse.accept(powerExpression.getAtomicExpression());
      }
    } else if (psiElement instanceof BuckAtomicExpression) {
      recurse.accept(((BuckAtomicExpression) psiElement).getExpressionListOrComprehension());
      recurse.accept(((BuckAtomicExpression) psiElement).getIdentifier());
    } else {
      LOG.info("Unparsed: " + psiElement.getNode().getElementType());
    }
  }

  /**
   * Returns a mapping from function definitions that start with the given prefix to their target
   * elements.
   */
  public static PsiElement findSymbolInPsiTree(PsiElement root, String name) {
    try {
      visitSymbols(
          root,
          (elementName, psiElement) -> {
            if (name.equals(elementName)) {
              throw new FoundSymbol(psiElement);
            }
          });
      return null;
    } catch (FoundSymbol e) {
      return e.element;
    }
  }
  /** Returns a mapping from symbols that start with the given prefix to their target elements. */
  public static Map<String, PsiElement> findSymbolsInPsiTree(PsiElement root, String namePrefix) {
    Map<String, PsiElement> results = new HashMap<>();
    visitSymbols(
        root,
        (name, element) -> {
          if (name.startsWith(namePrefix)) {
            results.put(name, element);
          }
        });
    return results;
  }
}
