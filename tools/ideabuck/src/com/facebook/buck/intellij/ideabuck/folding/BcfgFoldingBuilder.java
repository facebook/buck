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
package com.facebook.buck.intellij.ideabuck.folding;

import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgProperty;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgSection;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgTypes;
import com.intellij.lang.ASTNode;
import com.intellij.lang.folding.FoldingBuilderEx;
import com.intellij.lang.folding.FoldingDescriptor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Folds {@code .buckconfig} sections and properties. */
public class BcfgFoldingBuilder extends FoldingBuilderEx {

  /** Folds up all the properties in a section. */
  static @Nullable FoldingDescriptor sectionFoldingDescriptor(BcfgSection section) {
    return new FoldingDescriptor(section.getNode(), section.getTextRange()) {
      @Nullable
      @Override
      public String getPlaceholderText() {
        String sectionName = section.getSectionHeader().getSectionName().getText();
        int numProperties = section.getPropertyList().size();
        String propertiesPluralized = numProperties == 1 ? "property" : "properties";
        return "[" + sectionName + "] (" + numProperties + " " + propertiesPluralized + ")";
      }
    };
  }

  /** Create a folding descriptor for the given property. */
  static @Nullable FoldingDescriptor propertyFoldingDescriptor(BcfgProperty property) {
    int start = property.getAssign().getTextRange().getEndOffset();
    int end = property.getTextRange().getEndOffset();
    return new FoldingDescriptor(property.getNode(), new TextRange(start, end)) {
      @Nullable
      @Override
      public String getPlaceholderText() {
        return property.getPropertyValueAsText();
      }
    };
  }

  @NotNull
  @Override
  public FoldingDescriptor[] buildFoldRegions(
      @NotNull PsiElement root, @NotNull Document document, boolean quick) {
    List<FoldingDescriptor> descriptors = new ArrayList<>();
    PsiTreeUtil.findChildrenOfType(root, BcfgSection.class)
        .stream()
        .map(BcfgFoldingBuilder::sectionFoldingDescriptor)
        .forEach(descriptors::add);
    PsiTreeUtil.findChildrenOfType(root, BcfgProperty.class)
        .stream()
        .map(BcfgFoldingBuilder::propertyFoldingDescriptor)
        .filter(Objects::nonNull)
        .forEach(descriptors::add);

    return descriptors.toArray(new FoldingDescriptor[descriptors.size()]);
  }

  @Nullable
  @Override
  public String getPlaceholderText(@NotNull ASTNode node) {
    return "...";
  }

  @Override
  public boolean isCollapsedByDefault(@NotNull ASTNode node) {
    return node.getElementType().equals(BcfgTypes.SECTION)
        || node.getElementType().equals(BcfgTypes.PROPERTY);
  }
}
