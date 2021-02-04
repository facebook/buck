/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.intellij.ideabuck.completion;

import com.intellij.openapi.util.Key;
import com.intellij.psi.PsiFile;

public class BuckCompletionUtils {
  private static final Key<String> NON_BUCK_FILE_COMPLETIONS =
      Key.create("NON_BUCK_FILE_COMPLETIONS");

  /**
   * Not a real BUCK file. For example, the PsiFile of a text field that uses the Buck language to
   * provide auto-completions in the text field
   */
  public static boolean isNonBuckFile(PsiFile psiFile) {
    return psiFile.getUserData(NON_BUCK_FILE_COMPLETIONS) != null;
  }

  public static void setNonBuckFile(PsiFile psiFile) {
    psiFile.putUserData(NON_BUCK_FILE_COMPLETIONS, "");
  }
}
