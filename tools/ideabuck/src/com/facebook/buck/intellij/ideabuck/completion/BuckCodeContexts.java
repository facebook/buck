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
package com.facebook.buck.intellij.ideabuck.completion;

import com.facebook.buck.intellij.ideabuck.highlight.BuckSyntaxHighlighter;
import com.facebook.buck.intellij.ideabuck.lang.BuckLanguage;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckParameter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPrimary;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSingleExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckString;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSuite;
import com.intellij.codeInsight.template.EverywhereContextType;
import com.intellij.codeInsight.template.TemplateContextType;
import com.intellij.openapi.fileTypes.SyntaxHighlighter;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.Objects;
import java.util.stream.Stream;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Contexts and utilities to detect contexts in buck files. */
public abstract class BuckCodeContexts {

  private BuckCodeContexts() {
    // Utility class: do not instantiate
  }

  // Utility methods for determining context:

  /** Returns true if the element is at the start of a statement. */
  private static boolean isAtStatement(PsiElement element) {
    BuckStatement statement = PsiTreeUtil.getParentOfType(element, BuckStatement.class);
    return statement != null
        && statement.getTextRange().getStartOffset() == element.getTextRange().getStartOffset();
  }

  /**
   * Returns true if the element is at the start of a "declaration", essentially a top-level
   * statement, preferably at the top of a file.
   *
   * <p>Certain statement-like elements only appear there, e.g. {@code load()}.
   */
  private static boolean isAtDeclaration(PsiElement element) {
    BuckStatement statement = PsiTreeUtil.getParentOfType(element, BuckStatement.class);
    return isAtStatement(element)
        && PsiTreeUtil.getParentOfType(statement, BuckSuite.class) == null;
  }

  /**
   * Returns true if the element is at the start of a named parameter to a function call.
   *
   * <p>Note that this matches call-sites, not function definitions. For that, see {@link
   * #isAtNamedParameter(PsiElement)}.
   */
  private static boolean isAtNamedArgument(PsiElement element) {
    BuckArgument argument = PsiTreeUtil.getParentOfType(element, BuckArgument.class);
    return argument != null
        && argument.getTextRange().getStartOffset() == element.getTextRange().getStartOffset();
  }

  /**
   * Returns true if the element is at the start of a named parameter in a function definition.
   *
   * <p>Note that this matches function definitions, not call sites. For that, see * {@link
   * #isAtNamedArgument(PsiElement)}.
   */
  private static boolean isAtNamedParameter(PsiElement element) {
    BuckParameter parameter = PsiTreeUtil.getParentOfType(element, BuckParameter.class);
    return parameter != null
        && parameter.getTextRange().getStartOffset() == element.getTextRange().getStartOffset();
  }

  /**
   * Returns true if the element is at the start of an expression, i.e., in a context where a return
   * value is expected.
   */
  private static boolean isAtSingleExpression(PsiElement element) {
    BuckSingleExpression expression =
        PsiTreeUtil.getParentOfType(element, BuckSingleExpression.class);
    return expression != null
        && expression.getTextRange().getStartOffset() == element.getTextRange().getStartOffset();
  }

  private static boolean isAtBuckCode(PsiElement element) {
    return element.getLanguage().isKindOf(BuckLanguage.INSTANCE);
  }

  private static boolean isInsideString(PsiElement element) {
    BuckString buckString = PsiTreeUtil.getParentOfType(element, BuckString.class);
    if (buckString == null) {
      return false;
    }
    // Currently, BuckString also includes the '%' formatting directive :-(
    // This is wrong, but until that gets fixed, make sure this is in the
    // quoted part of the string and not in some other part of the string.
    BuckPrimary primary = buckString.getPrimary();
    if (PsiTreeUtil.isAncestor(primary, element, false)) {
      return false;
    }
    return Stream.of(
            buckString.getSingleQuotedString(),
            buckString.getDoubleQuotedString(),
            buckString.getSingleQuotedDocString(),
            buckString.getDoubleQuotedDocString())
        .filter(Objects::nonNull)
        .findAny()
        .filter(parent -> PsiTreeUtil.isAncestor(parent, element, false))
        .isPresent();
  }

  // Utility classes that providing contexts for templates:

  /** Heavily modeled after {@link com.intellij.codeInsight.template.JavaCodeContextType}. */
  public abstract static class BaseTemplateContext extends TemplateContextType {
    BaseTemplateContext(
        @NotNull @NonNls String id,
        @NotNull String presentableName,
        @Nullable Class<? extends TemplateContextType> baseContextType) {
      super(id, presentableName, baseContextType);
    }

    @Override
    public boolean isInContext(@NotNull final PsiFile file, final int offset) {
      PsiElement element = file.findElementAt(offset);
      return element != null && appliesTo(element, offset);
    }

    abstract boolean appliesTo(PsiElement element, int offset);

    @NotNull
    @Override
    public SyntaxHighlighter createHighlighter() {
      return new BuckSyntaxHighlighter();
    }
  }

  /** A statement at any indentation level. */
  public static class Generic extends BaseTemplateContext {
    public Generic() {
      super("BUCK_CODE", "Buck", EverywhereContextType.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isAtBuckCode(element);
    }
  }

  /**
   * Top-level declaration.
   *
   * <p>For example, {@code load()} statements can only be top-level declarations.
   */
  public static class Declaration extends BaseTemplateContext {
    public Declaration() {
      super("BUCK_DECLARATION", "Declaration", Generic.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isAtDeclaration(element);
    }
  }

  /** Named argument to a function call (does not apply to function definitions). */
  public static class NamedArgument extends BaseTemplateContext {
    public NamedArgument() {
      super("BUCK_NAMED_ARGUMENT", "Named Argument", Generic.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isAtNamedArgument(element);
    }
  }

  /** Named argument to a function definition (does not apply to function call). */
  public static class NamedParameter extends BaseTemplateContext {
    public NamedParameter() {
      super("BUCK_NAMED_PARAMETER", "Named Parameter", Generic.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isAtNamedParameter(element);
    }
  }

  /** A statement at any indentation level. */
  public static class Statement extends BaseTemplateContext {
    public Statement() {
      super("BUCK_STATEMENT", "Statement", Generic.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isAtStatement(element);
    }
  }

  /** Any buck expression. */
  public static class SingleExpression extends BaseTemplateContext {
    public SingleExpression() {
      super("BUCK_EXPRESSION", "Expression", Generic.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isAtSingleExpression(element);
    }
  }

  /** Inside the body of a buck string. */
  public static class InString extends BaseTemplateContext {
    public InString() {
      super("BUCK_STRING", "String", Generic.class);
    }

    @Override
    boolean appliesTo(PsiElement element, int offset) {
      return isInsideString(element);
    }
  }
}
