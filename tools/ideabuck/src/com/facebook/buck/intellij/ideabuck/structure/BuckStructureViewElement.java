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
package com.facebook.buck.intellij.ideabuck.structure;

import com.facebook.buck.intellij.ideabuck.icons.BuckIcons;
import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArgument;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckCompoundStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckExpressionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckFunctionTrailer;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckLoadCall;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPowerExpression;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSimpleStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckSmallStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckStatement;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckExpressionImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckFunctionDefinitionImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckFunctionTrailerImpl;
import com.facebook.buck.intellij.ideabuck.lang.psi.impl.BuckLoadArgumentImpl;
import com.facebook.buck.intellij.ideabuck.util.BuckPsiUtils;
import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.intellij.icons.AllIcons;
import com.intellij.icons.AllIcons.ToolbarDecorator;
import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.SortableTreeElement;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.psi.NavigatablePsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import javax.swing.Icon;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Base class for elements in the Structure view for Buck files. */
public abstract class BuckStructureViewElement<E extends NavigatablePsiElement>
    implements StructureViewTreeElement, SortableTreeElement {

  /** Creates a structure view element for the given psi element. */
  @Nullable
  public static BuckStructureViewElement forElement(NavigatablePsiElement element) {
    if (element instanceof BuckFile) {
      return new ForFile((BuckFile) element);
    } else if (element instanceof BuckFunctionDefinitionImpl) {
      return new ForFunctionDefinition((BuckFunctionDefinitionImpl) element);
    } else if (element instanceof BuckFunctionTrailerImpl) {
      return new ForFunctionTrailer((BuckFunctionTrailerImpl) element);
    } else if (element instanceof BuckLoadArgumentImpl) {
      return new ForLoadArgument((BuckLoadArgumentImpl) element);
    } else if (element instanceof BuckExpressionImpl) {
      return new ForExpression((BuckExpressionImpl) element);
    } else {
      return null;
    }
  }

  private E element;

  protected BuckStructureViewElement(E element) {
    this.element = element;
  }

  @Override
  public E getValue() {
    return element;
  }

  @Override
  public void navigate(boolean requestFocus) {
    element.navigate(requestFocus);
  }

  @Override
  public boolean canNavigate() {
    return element.canNavigate();
  }

  @Override
  public boolean canNavigateToSource() {
    return element.canNavigateToSource();
  }

  @NotNull
  @Override
  public String getAlphaSortKey() {
    String name = element.getName();
    return name != null ? name : "";
  }

  @NotNull
  @Override
  public ItemPresentation getPresentation() {
    ItemPresentation presentation = element.getPresentation();
    return presentation != null ? presentation : new PresentationData();
  }

  @Override
  @NotNull
  public TreeElement[] getChildren() {
    return TreeElement.EMPTY_ARRAY;
  }

  /** For a buck file, a heirarchy of its top-level statements. */
  static class ForFile extends BuckStructureViewElement<BuckFile> {
    public ForFile(BuckFile buckFile) {
      super(buckFile);
    }

    @Override
    @NotNull
    public TreeElement[] getChildren() {
      List<TreeElement> treeElements = new ArrayList<>();
      List<BuckStatement> statementList = getValue().getStatementList();
      for (BuckStatement statement : statementList) {
        if (statement == null) {
          continue;
        }
        // Mark function definition, e.g. def foo(args): body
        Optional.ofNullable(statement.getCompoundStatement())
            .map(BuckCompoundStatement::getFunctionDefinition)
            .map(BuckFunctionDefinitionImpl.class::cast)
            .map(ForFunctionDefinition::new)
            .ifPresent(treeElements::add);
        List<BuckSmallStatement> smallStatementList =
            Optional.of(statement)
                .map(BuckStatement::getSimpleStatement)
                .map(BuckSimpleStatement::getSmallStatementList)
                .orElse(Collections.emptyList());

        for (BuckSmallStatement smallStatement : smallStatementList) {
          // Mark rules, e.g., genrule(name="foo", ...)
          PsiTreeUtil.findChildrenOfAnyType(smallStatement, BuckFunctionTrailer.class)
              .forEach(
                  functionTrailer -> {
                    if (functionTrailer.getArgumentList().stream()
                        .anyMatch(argument -> "name".equals(argument.getName()))) {
                      treeElements.add(
                          new ForFunctionTrailer((BuckFunctionTrailerImpl) functionTrailer));
                    }
                  });

          // Mark load call, e.g., load("@src.bzl", "symbol")
          Optional.ofNullable(smallStatement.getLoadCall()).map(BuckLoadCall::getLoadArgumentList)
              .orElse(Collections.emptyList()).stream()
              .map(BuckLoadArgumentImpl.class::cast)
              .map(ForLoadArgument::new)
              .forEach(treeElements::add);
          // Mark definitions, e.g.,  FOO = "value"
          Optional.ofNullable(smallStatement.getExpressionStatement())
              .filter(es -> es.getAugmentAssignment() == null)
              .map(BuckExpressionStatement::getExpressionListList)
              .map(list -> list.subList(0, list.size() - 1).stream())
              .orElse(Stream.empty())
              .flatMap(expList -> expList.getExpressionList().stream())
              .map(BuckExpressionImpl.class::cast)
              .map(ForExpression::new)
              .forEach(treeElements::add);
        }
      }
      return treeElements.toArray(new TreeElement[0]);
    }
  }

  /**
   * For a function call with a "name" parameter, assume it is a rule, and show the name (and rule
   * type).
   */
  static class ForFunctionTrailer extends BuckStructureViewElement<BuckFunctionTrailerImpl> {
    public ForFunctionTrailer(BuckFunctionTrailerImpl functionTrailer) {
      super(functionTrailer);
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
      return new ItemPresentation() {
        @Nullable
        @Override
        public String getPresentableText() {
          return getValue().getArgumentList().stream()
              .filter(arg -> "name".equals(arg.getName()))
              .findFirst()
              .map(BuckArgument::getExpression)
              .map(expression -> Optional.of(expression)
                  .map(BuckPsiUtils::getStringValueFromExpression)
                  .orElse(expression.getText()))
              .orElse("<unnamed>");
        }

        @Nullable
        @Override
        public String getLocationString() {
          BuckExpressionTrailer expressionTrailer = (BuckExpressionTrailer) getValue().getParent();
          BuckPowerExpression powerExpression = (BuckPowerExpression) expressionTrailer.getParent();
          int startOffset = powerExpression.getTextRange().getStartOffset();
          int endOffset = getValue().getTextRange().getStartOffset();
          return "(" + powerExpression.getText().substring(0, endOffset - startOffset) + ")";
        }

        @Nullable
        @Override
        public Icon getIcon(boolean unused) {
          return BuckIcons.FILE_TYPE;
        }
      };
    }
  }

  /** For a function definition, show the function name. */
  static class ForFunctionDefinition extends BuckStructureViewElement<BuckFunctionDefinitionImpl> {
    public ForFunctionDefinition(BuckFunctionDefinitionImpl functionDefinition) {
      super(functionDefinition);
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
      return new ItemPresentation() {
        @Nullable
        @Override
        public String getPresentableText() {
          return getValue().getName();
        }

        @Nullable
        @Override
        public String getLocationString() {
          return null;
        }

        @Nullable
        @Override
        public Icon getIcon(boolean unused) {
          return AllIcons.Nodes.Method;
        }
      };
    }
  }

  /** For an expression, show its text representation */
  static class ForExpression extends BuckStructureViewElement<BuckExpressionImpl> {
    public ForExpression(BuckExpressionImpl expression) {
      super(expression);
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
      return new ItemPresentation() {
        @Nullable
        @Override
        public String getPresentableText() {
          return getValue().getText();
        }

        @Nullable
        @Override
        public String getLocationString() {
          return null;
        }

        @Nullable
        @Override
        public Icon getIcon(boolean unused) {
          return AllIcons.Nodes.Variable;
        }
      };
    }
  }

  /** For a load argument, show the symbol loaded. */
  static class ForLoadArgument extends BuckStructureViewElement<BuckLoadArgumentImpl> {

    public ForLoadArgument(BuckLoadArgumentImpl loadArgument) {
      super(loadArgument);
    }

    @NotNull
    @Override
    public ItemPresentation getPresentation() {
      return new ItemPresentation() {
        @Nullable
        @Override
        public String getPresentableText() {
          return "load:" + getValue().getName();
        }

        @Nullable
        @Override
        public String getLocationString() {
          return Optional.of(getValue())
              .map(e -> PsiTreeUtil.getParentOfType(e, BuckLoadCall.class))
              .map(loadCall -> loadCall.getLoadTargetArgument())
              .map(target -> target.getString().getText())
              .orElse(null);
        }

        @Nullable
        @Override
        public Icon getIcon(boolean unused) {
          return ToolbarDecorator.Import; // TODO: Come up with better icon
        }
      };
    }
  }
}
