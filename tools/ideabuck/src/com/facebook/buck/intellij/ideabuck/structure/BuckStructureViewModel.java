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

import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewModelBase;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.util.treeView.smartTree.ActionPresentation;
import com.intellij.ide.util.treeView.smartTree.ActionPresentationData;
import com.intellij.ide.util.treeView.smartTree.Filter;
import com.intellij.ide.util.treeView.smartTree.Sorter;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.PlatformIcons;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

/** Defines a model for viewing the outline structure of a buck file. */
public class BuckStructureViewModel extends StructureViewModelBase
    implements StructureViewModel.ElementInfoProvider {

  /** Filter for {@code load()} statements in Buck file structure view. */
  private static class ShowLoads implements Filter {
    @NonNls public static final String ID = "SHOW_BUCK_LOADS";

    @Override
    public boolean isVisible(TreeElement treeNode) {
      return !(treeNode instanceof BuckStructureViewElement.ForLoadArgument);
    }

    @Override
    public boolean isReverted() {
      return true;
    }

    @NotNull
    @Override
    public ActionPresentation getPresentation() {
      return new ActionPresentationData("Show load statements", null, PlatformIcons.IMPORT_ICON);
    }

    @NotNull
    @Override
    public String getName() {
      return ID;
    }
  }

  /** Filter for local variables in Buck file structure view. */
  private static class ShowVariables implements Filter {
    @NonNls public static final String ID = "SHOW_BUCK_VARIABLES";

    @Override
    public boolean isVisible(TreeElement treeNode) {
      return !(treeNode instanceof BuckStructureViewElement.ForExpression);
    }

    @Override
    public boolean isReverted() {
      return true;
    }

    @NotNull
    @Override
    public ActionPresentation getPresentation() {
      return new ActionPresentationData(
          "Show variable declarations", null, PlatformIcons.FIELD_ICON);
    }

    @NotNull
    @Override
    public String getName() {
      return ID;
    }
  }

  /** Filter for private symbols in Buck file structure view. */
  private static class ShowNonPublic implements Filter {
    @NonNls public static final String ID = "SHOW_BUCK_NON_PUBLIC";

    @Override
    public boolean isVisible(TreeElement treeNode) {
      if (treeNode instanceof BuckStructureViewElement) {
        String text = treeNode.getPresentation().getPresentableText();
        return !text.trim().startsWith("_"); // Symbols starting with "_" are private
      } else {
        return true;
      }
    }

    @Override
    public boolean isReverted() {
      return true;
    }

    @NotNull
    @Override
    public ActionPresentation getPresentation() {
      return new ActionPresentationData("Show non-public", null, PlatformIcons.PRIVATE_ICON);
    }

    @NotNull
    @Override
    public String getName() {
      return ID;
    }
  }

  public BuckStructureViewModel(PsiFile psiFile) {
    super(psiFile, BuckStructureViewElement.forElement(psiFile));
  }

  @Override
  @NotNull
  public Filter[] getFilters() {
    // return super.getFilters();
    return new Filter[] {
      new ShowLoads(), new ShowVariables(), new ShowNonPublic(),
    };
  }

  @Override
  @NotNull
  public Sorter[] getSorters() {
    return new Sorter[] {Sorter.ALPHA_SORTER};
  }

  @Override
  public boolean isAlwaysShowsPlus(StructureViewTreeElement element) {
    return false;
  }

  @Override
  public boolean isAlwaysLeaf(StructureViewTreeElement element) {
    return false;
  }
}
