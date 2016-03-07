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
  private static final String RESOURCES_KEYWORD = "resources";
  private static final String TESTS_KEYWORD = "tests";
  private static final String VISIBILITY_KEYWORD = "visibility";
  private static final String PROVIDED_DEPS_KEYWORD = "provided_deps";

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
      if (lValue == null ||
          (!lValue.getText().equals(DEPENDENCIES_KEYWORD) &&
          !lValue.getText().equals(RESOURCES_KEYWORD) &&
          !lValue.getText().equals(TESTS_KEYWORD) &&
          !lValue.getText().equals(PROVIDED_DEPS_KEYWORD) &&
          !lValue.getText().equals(VISIBILITY_KEYWORD))) {
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
        // Split into target and path
        String[] targetPathArray1 = e1.getText().split(":");
        String path1 = "";
        String target1;

        if (targetPathArray1.length == 2) {
          path1 = targetPathArray1[0];
          target1 = targetPathArray1[1];
        } else {
          target1 = targetPathArray1[0];
        }

        // Split into target and path
        String[] targetPathArray2 = e2.getText().split(":");
        String path2 = "";
        String target2;
        if (targetPathArray2.length == 2) {
          path2 = targetPathArray2[0];
          target2 = targetPathArray2[1];
        } else {
          target2 = targetPathArray2[0];
        }

        // Split the paths into separate tokens
        String[] splitPath1 = path1.split("/");
        String[] splitPath2 = path2.split("/");

        int maxChar = Math.min(splitPath1.length, splitPath2.length);
        int result;
        for (int i = 0; i < maxChar; i++) {
          // compare the tokens
          result = splitPath1[i].compareTo(splitPath2[i]);
          // if they're different return
          if (result != 0) {
            return result;
          }
        }

        // if all the tokens are similar up until the shortest of the strings
        // put the shorter one upper
        result = splitPath1.length - splitPath2.length;
        if (result == 0) {
          // if they have the same path then compare targets
          return target1.compareTo(target2);
        } else {
          return result;
        }
      }
    });

    PsiElement[] oldValues = new PsiElement[arrayValues.length];
    for (int i = 0; i < arrayValues.length; ++i) {
      oldValues[i] = arrayValues[i].copy();
    }

    for (int i = 0; i < arrayValues.length; ++i) {
      arrayElements.getChildren()[i].replace(oldValues[i]);
    }
  }
}
