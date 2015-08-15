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

package com.facebook.buck.intellij.plugin.format;

import com.facebook.buck.intellij.plugin.lang.psi.BuckArrayElements;
import com.facebook.buck.intellij.plugin.lang.psi.BuckProperty;
import com.facebook.buck.intellij.plugin.lang.psi.BuckPropertyLvalue;
import com.facebook.buck.intellij.plugin.lang.psi.BuckValue;
import com.facebook.buck.intellij.plugin.lang.psi.BuckValueArray;
import com.facebook.buck.intellij.plugin.lang.psi.BuckVisitor;

import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A utility class for sorting buck dependencies alphabetically.
 */
public final class DependenciesOptimizer {

  private DependenciesOptimizer() {
  }

  private static final String DEPENDENCIES_KEYWORD = "deps";

  public static void optimzeDeps(PsiFile file) {
    final PropertyVisitor visitor = new PropertyVisitor();
    file.accept(new BuckVisitor() {
      @Override
      public void visitElement(PsiElement node) {
        node.acceptChildren(this);
        node.accept(visitor);
      }
    });

    // Commit modifications.
    final PsiDocumentManager manager = PsiDocumentManager.getInstance(file.getProject());
    manager.doPostponedOperationsAndUnblockDocument(manager.getDocument(file));
  }

  private static class PropertyVisitor extends BuckVisitor {
    @Override
    public void visitProperty(BuckProperty property) {
      BuckPropertyLvalue lValue = property.getPropertyLvalue();
      if (lValue == null || !lValue.getText().equals(DEPENDENCIES_KEYWORD)) {
        return;
      }

      List<BuckValue> values = property.getExpression().getValueList();
      for (BuckValue value : values) {
        BuckValueArray array = value.getValueArray();
        if (array != null) {
          sortArray(array);
        }
      }
    }
  }

  private static void sortArray(BuckValueArray array) {
    BuckArrayElements arrayElements = array.getArrayElements();
    PsiElement[] arrayValues = arrayElements.getChildren();
    Arrays.sort(arrayValues, new Comparator<PsiElement>() {
          @Override
          public int compare(PsiElement e1, PsiElement e2) {
            return compareDependencyStrings(e1.getText(), e2.getText());
          }
        }
    );
    PsiElement[] oldValues = new PsiElement[arrayValues.length];
    for (int i = 0; i < arrayValues.length; ++i) {
      oldValues[i] = arrayValues[i].copy();
    }

    for (int i = 0; i < arrayValues.length; ++i) {
      arrayElements.getChildren()[i].replace(oldValues[i]);
    }
  }

  /**
   * Use our own method to compare 'deps' stings.
   * 'deps' should be sorted with local references ':' preceding any cross-repo references '@'
   * e.g :inner, //world:empty, //world/asia:jp, //world/europe:uk, @mars, @moon
   */
  private static int compareDependencyStrings(String baseString, String anotherString) {
    for (int i = 0; i < Math.min(baseString.length(), anotherString.length()); ++i) {
      char c1 = baseString.charAt(i);
      char c2 = anotherString.charAt(i);
      if (c1 != c2) {
        // ':' should go first when compare ':' with '/' for Buck dependencies.
        if ((c1 == ':' && c2 == '/') || (c1 == '/' && c2 == ':')) {
          return c2 - c1;
        } else {
          return c1 - c2;
        }
      }
    }
    return baseString.length() - anotherString.length();
  }
}
