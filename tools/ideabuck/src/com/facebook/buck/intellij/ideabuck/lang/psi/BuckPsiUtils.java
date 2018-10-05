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

package com.facebook.buck.intellij.ideabuck.lang.psi;

import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jetbrains.annotations.Nullable;

public final class BuckPsiUtils {

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

  /**
   * Return the text content if the given BuckExpression has only one string value. Return null if
   * this expression has multiple values, for example: "a" + "b"
   */
  @Nullable
  public static String getStringValueFromExpression(BuckSingleExpression expression) {
    List<BuckPrimaryWithSuffix> values = expression.getPrimaryWithSuffixList();
    if (values.size() != 1) {
      return null;
    }
    BuckPrimaryWithSuffix buckPrimaryWithSuffix = values.get(0);
    if (!buckPrimaryWithSuffix.getDotSuffixList().isEmpty()) {
      return null; // "string {} are unsupported so far".format("methods").trim()
    }
    if (!buckPrimaryWithSuffix.getSliceSuffixList().isEmpty()) {
      return null; // "<<slices>>"[2:-2]
    }
    BuckPrimary buckPrimary = buckPrimaryWithSuffix.getPrimary();
    return getStringValueFromBuckString(buckPrimary.getString());
  }

  /**
   * Returns the text content of the given string (without the appropriate quoting).
   *
   * <p>This method accepts elements that are either {@link BuckString} elements or any of the
   * various {@link #STRING_LITERALS}.
   *
   * <p>Note that this method is currently underdeveloped and hacky. It does not apply percent-style
   * formatting (if such formatting is used, this method returns null), nor does it process escape
   * sequences (these sequences currently appear in their raw form in the string).
   */
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
    BuckString buckString = (BuckString) stringElement;
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
  public static PsiElement findTargetInPsiTree(PsiElement root, String name) {
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
}
