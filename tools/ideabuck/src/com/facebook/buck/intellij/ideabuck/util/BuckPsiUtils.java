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
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckAssignmentTarget;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckAssignmentTargetList;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckCompoundStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionCallSuffix;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionDefinition;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIfStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPrimary;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPrimaryWithSuffix;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPropertyLvalue;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSimpleStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSingleExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSmallStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSuite;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.intellij.lang.ASTNode;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jetbrains.annotations.Nullable;

public final class BuckPsiUtils {

  private static final Logger LOG = Logger.getInstance(BuckPsiUtils.class);

  public static final TokenSet STRING_LITERALS =
      TokenSet.create(
          BuckTypes.SINGLE_QUOTED_STRING,
          BuckTypes.DOUBLE_QUOTED_STRING,
          BuckTypes.SINGLE_QUOTED_DOC_STRING,
          BuckTypes.DOUBLE_QUOTED_DOC_STRING);

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

  /** @see #hasElementType(com.intellij.lang.ASTNode, com.intellij.psi.tree.TokenSet) */
  public static boolean hasElementType(ASTNode node, IElementType... types) {
    return hasElementType(node, TokenSet.create(types));
  }

  /** @see #hasElementType(com.intellij.lang.ASTNode, com.intellij.psi.tree.TokenSet) */
  public static boolean hasElementType(PsiElement element, TokenSet set) {
    return element.getNode() != null && hasElementType(element.getNode(), set);
  }

  /** @see #hasElementType(com.intellij.lang.ASTNode, com.intellij.psi.tree.IElementType...) */
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
  public static String getStringValueFromExpression(BuckSingleExpression expression) {
    return Optional.of(expression)
        .filter(e -> e.getSingleExpressionList().isEmpty())
        .map(BuckSingleExpression::getPrimaryWithSuffix)
        .filter(e -> e.getDotSuffixList().isEmpty()) // "stri{}".format("ng") unsupported
        .filter(e -> e.getSliceSuffixList().isEmpty()) // "<<slices>>"[2:-2] unsupported
        .map(BuckPrimaryWithSuffix::getPrimary)
        .map(BuckPrimary::getString)
        .map(BuckPsiUtils::getStringValueFromBuckString)
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
    if (!hasElementType(stringElement, BuckTypes.STRING)) {
      return null;
    }
    return getStringValueFromBuckString((BuckString) stringElement);
  }

  /**
   * Returns the text content of the given string (without the appropriate quoting).
   *
   * <p>Note that this method is currently underdeveloped and hacky. It does not apply percent-style
   * formatting (if such formatting is used, this method returns null), nor does it process escape
   * sequences (these sequences currently appear in their raw form in the string).
   */
  public static String getStringValueFromBuckString(BuckString buckString) {
    if (buckString.getPrimary() != null) {
      return null; // "%s %s" % ("percent", "formatting")
    }
    PsiElement quotedElement = buckString.getSingleQuotedString();
    if (quotedElement == null) {
      quotedElement = buckString.getDoubleQuotedString();
    }
    if (quotedElement != null) {
      String text = quotedElement.getText();
      return text.length() >= 2 ? text.substring(1, text.length() - 1) : null;
    }

    PsiElement tripleQuotedElement = buckString.getSingleQuotedDocString();
    if (tripleQuotedElement == null) {
      tripleQuotedElement = buckString.getDoubleQuotedDocString();
    }
    if (tripleQuotedElement != null) {
      String text = tripleQuotedElement.getText();
      return text.length() >= 6 ? text.substring(3, text.length() - 3) : null;
    }
    return null;
  }

  /**
   * Returns the definition for a rule with the given target name in the given root, or {@code null}
   * if it cannot be found.
   */
  @Nullable
  public static BuckFunctionCall findTargetInPsiTree(PsiElement root, String name) {
    for (BuckFunctionCall buckRuleBlock :
        PsiTreeUtil.findChildrenOfType(root, BuckFunctionCall.class)) {
      BuckFunctionCallSuffix buckRuleBody = buckRuleBlock.getFunctionCallSuffix();
      for (BuckArgument buckProperty :
          PsiTreeUtil.findChildrenOfType(buckRuleBody, BuckArgument.class)) {
        if (!Optional.ofNullable(buckProperty.getPropertyLvalue())
            .map(lvalue -> lvalue.getIdentifier().getText())
            .filter("name"::equals)
            .isPresent()) {
          continue;
        }
        if (name.equals(getStringValueFromExpression(buckProperty.getSingleExpression()))) {
          return buckRuleBlock;
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
    for (BuckFunctionCall buckRuleBlock :
        PsiTreeUtil.findChildrenOfType(psiFile, BuckFunctionCall.class)) {
      BuckFunctionCallSuffix buckRuleBody = buckRuleBlock.getFunctionCallSuffix();
      for (BuckArgument buckArgument :
          PsiTreeUtil.findChildrenOfType(buckRuleBody, BuckArgument.class)) {
        BuckPropertyLvalue propertyLvalue = buckArgument.getPropertyLvalue();
        if (propertyLvalue == null || !"name".equals(propertyLvalue.getText())) {
          continue;
        }
        String name = BuckPsiUtils.getStringValueFromExpression(buckArgument.getSingleExpression());
        if (name != null) {
          if (name.startsWith(namePrefix)) {
            targetsByName.put(name, buckRuleBlock);
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
    } else if (psiElement.getNode().getElementType() == BuckTypes.IDENTIFIER) {
      visitor.visit(psiElement.getText(), psiElement);
    } else if (psiElement instanceof BuckLoadCall) {
      ((BuckLoadCall) psiElement).getLoadArgumentList().forEach(recurse);
    } else if (psiElement instanceof BuckLoadArgument) {
      PsiElement identifier = ((BuckLoadArgument) psiElement).getIdentifier();
      if (identifier != null) {
        recurse.accept(identifier);
      } else {
        BuckString nameElement = ((BuckLoadArgument) psiElement).getString();
        visitor.visit(getStringValueFromBuckString(nameElement), nameElement);
      }
    } else if (psiElement instanceof BuckFunctionDefinition) {
      recurse.accept(((BuckFunctionDefinition) psiElement).getIdentifier());
    } else if (psiElement instanceof BuckStatement) {
      recurse.accept(((BuckStatement) psiElement).getSimpleStatement());
      recurse.accept(((BuckStatement) psiElement).getCompoundStatement());
    } else if (psiElement instanceof BuckIfStatement) {
      ((BuckIfStatement) psiElement).getSingleExpressionList().forEach(recurse);
      ((BuckIfStatement) psiElement).getSuiteList().forEach(recurse);
    } else if (psiElement instanceof BuckSimpleStatement) {
      ((BuckSimpleStatement) psiElement).getSmallStatementList().forEach(recurse);
    } else if (psiElement instanceof BuckCompoundStatement) {
      recurse.accept(((BuckCompoundStatement) psiElement).getForStatement());
      recurse.accept(((BuckCompoundStatement) psiElement).getIfStatement());
      recurse.accept(((BuckCompoundStatement) psiElement).getFunctionDefinition());
    } else if (psiElement instanceof BuckSmallStatement) {
      recurse.accept(((BuckSmallStatement) psiElement).getAssignmentTarget());
      ((BuckSmallStatement) psiElement).getAssignmentTargetListList().forEach(recurse);
    } else if (psiElement instanceof BuckSuite) {
      recurse.accept(((BuckSuite) psiElement).getSimpleStatement());
      ((BuckSuite) psiElement).getStatementList().forEach(recurse);
    } else if (psiElement instanceof BuckAssignmentTarget) {
      if (((BuckAssignmentTarget) psiElement).getPrimary() == null) {
        recurse.accept(((BuckAssignmentTarget) psiElement).getIdentifier());
        recurse.accept(((BuckAssignmentTarget) psiElement).getAssignmentTargetList());
      }
    } else if (psiElement instanceof BuckAssignmentTargetList) {
      ((BuckAssignmentTargetList) psiElement).getAssignmentTargetList().forEach(recurse);
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
