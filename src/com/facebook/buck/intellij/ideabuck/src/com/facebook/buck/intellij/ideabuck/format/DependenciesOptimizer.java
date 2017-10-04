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

package com.facebook.buck.intellij.ideabuck.format;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckArrayElements;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckProperty;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPropertyLvalue;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckValue;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckValueArray;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckVisitor;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.Ordering;
import com.intellij.lang.ImportOptimizer;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

/** A utility class for sorting buck dependencies alphabetically. */
public class DependenciesOptimizer {
  private static final Logger LOG = Logger.getInstance(DependenciesOptimizer.class);

  private static final String DEPENDENCIES_KEYWORD = "deps";
  private static final String PROVIDED_DEPENDENCIES_KEYWORD = "provided_deps";
  private static final String EXPORTED_DEPENDENCIES_KEYWORD = "exported_deps";

  private static final String DEPENDENCIES_FORMAT_PATTERN = "%2$d dependenc%3$s pruned";

  private DependenciesOptimizer() {}

  static class OptimizerInstance implements ImportOptimizer.CollectingInfoRunnable {

    final PsiFile psiFile;
    int sortedArrays = 0;
    int prunedDependencies = 0;

    OptimizerInstance(PsiFile psiFile) {
      this.psiFile = psiFile;
    }

    @Override
    public void run() {
      optimizeDeps(psiFile);
    }

    @org.jetbrains.annotations.Nullable
    @Override
    public String getUserNotificationInfo() {
      if (sortedArrays == 0 && prunedDependencies == 0) {
        return "Dependencies were already sorted properly";
      }

      String result =
          sortedArrays == 0
              ? null
              : String.format("%d array%s sorted", sortedArrays, sortedArrays == 1 ? "" : "s");

      if (prunedDependencies > 0) {
        result =
            String.format(
                sortedArrays == 0
                    ? DEPENDENCIES_FORMAT_PATTERN
                    : "%s; " + DEPENDENCIES_FORMAT_PATTERN,
                result,
                prunedDependencies,
                prunedDependencies == 1 ? "y" : "ies");
      }

      return result;
    }

    private void optimizeDeps(@NotNull PsiFile file) {
      final PropertyVisitor visitor = new PropertyVisitor();
      file.accept(
          new BuckVisitor() {
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

    private void sortArray(BuckValueArray array) {
      BuckArrayElements arrayElements = array.getArrayElements();
      PsiElement[] arrayValues = arrayElements.getChildren();
      PsiElement[] unsortedArrayValues = Arrays.copyOf(arrayValues, arrayValues.length);
      Arrays.sort(arrayValues, PSI_SORT_ORDER);

      boolean changed = false;
      PsiElement[] copiedValues = new PsiElement[arrayValues.length];
      for (int index = 0; index < arrayValues.length; ++index) {
        PsiElement newValue = arrayValues[index];
        copiedValues[index] = newValue.copy();
        changed |= unsortedArrayValues[index] != newValue; // |= is cheaper than a branch
      }
      if (changed) {
        sortedArrays += 1;
      }

      for (int index = 0; index < copiedValues.length; ++index) {
        arrayElements.getChildren()[index].replace(copiedValues[index]);
      }
    }

    private void removeDuplicates(BuckValueArray array) {
      // These are NOT the same copies we put back in sortArray()
      BuckArrayElements arrayElements = array.getArrayElements();
      PsiElement[] arrayValues = arrayElements.getChildren();
      for (int index = arrayValues.length - 1; index > 0; --index) {
        PsiElement element = arrayValues[index];
        String elementText = element.getText();
        int prior = index - 1;
        while (prior >= 0 && elementText.equals(arrayValues[prior].getText())) {
          prior -= 1;
        }
        // prior now points to the first PsiElement that is not a dup of element
        int deletions = index - 1 - prior;
        if (deletions == 0) {
          continue; // there are no dups of arrayValues[index]
        }
        LOG.info(String.format("Deleting children %d to %d", prior + 1, index - 1));
        prunedDependencies += deletions;
        PsiElement first = arrayValues[prior + 1];
        PsiElement last =
            // NOT arrayValues[index - 1], because we want to delete trailing comma and whitespace
            element.getPrevSibling();

        arrayElements.deleteChildRange(first, last);
        index = prior + 1; // resume scan at first PsiElement that didn't match
      }
    }

    private class PropertyVisitor extends BuckVisitor {
      @Override
      public void visitProperty(@NotNull BuckProperty property) {
        BuckPropertyLvalue lValue = property.getPropertyLvalue();
        if (lValue == null
            || (!DEPENDENCIES_KEYWORD.equals(lValue.getText())
                && !PROVIDED_DEPENDENCIES_KEYWORD.equals(lValue.getText())
                && !EXPORTED_DEPENDENCIES_KEYWORD.equals(lValue.getText()))) {
          return;
        }

        List<BuckValue> values = property.getExpression().getValueList();
        for (BuckValue value : values) {
          BuckValueArray array = value.getValueArray();
          if (array != null) {
            sortArray(array);
            removeDuplicates(array);
          }
        }
      }
    }
  }

  /**
   * Use our own method to compare 'deps' strings. 'deps' should be sorted with local references ':'
   * preceding any cross-repo references 'cell//' e.g :inner, //world:empty, //world/asia:jp,
   * mars//olympus, moon//sea:tranquility
   */
  private static int compareDependencyStrings(String baseString, String anotherString) {
    int endBaseString = baseString.length();
    int endAnotherString = anotherString.length();
    int i = 0;
    int j = 0;
    while (i < endBaseString && j < endAnotherString) {
      char c1 = baseString.charAt(i);
      char c2 = anotherString.charAt(j);
      if (c1 == ' ') {
        i++;
        continue;
      } else if (c2 == ' ') {
        j++;
        continue;
      } else if (c1 == c2) {
        i++;
        j++;
        continue;
      } else if (c1 == ':') {
        return -1;
      } else if (c2 == ':') {
        return 1;
      } else if (c1 == '/') {
        return -1;
      } else if (c2 == '/') {
        return 1;
      } else if (c1 < c2) {
        return -1;
      } else {
        return 1;
      }
    }
    return baseString.compareTo(anotherString);
  }

  private static final Ordering<String> SORT_ORDER =
      Ordering.from(
          new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
              return compareDependencyStrings(o1, o2);
            }
          });

  private static final Ordering<PsiElement> PSI_SORT_ORDER =
      SORT_ORDER.onResultOf(
          new Function<PsiElement, String>() {
            @Override
            public String apply(@Nullable PsiElement psiElement) {
              return psiElement.getText();
            }
          });

  /** Returns the preferred sort order of dependencies. */
  @VisibleForTesting
  static Ordering<String> sortOrder() {
    return SORT_ORDER;
  }
}
