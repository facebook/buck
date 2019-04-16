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
package com.facebook.buck.intellij.ideabuck.lang;

import com.facebook.buck.intellij.ideabuck.lang.psi.BuckIdentifier;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** Helper methods for working with {@link BuckIdentifier} objects. */
public class BuckIdentifierUtil {

  /** Returns all known identifiers that match the given key name. */
  public static List<BuckIdentifier> findIdentifiers(Project project, String key) {
    List<BuckIdentifier> result = new ArrayList<>();
    Collection<VirtualFile> virtualFiles =
        FileTypeIndex.getFiles(BuckFileType.INSTANCE, GlobalSearchScope.allScope(project));
    for (VirtualFile virtualFile : virtualFiles) {
      PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
      if (psiFile instanceof BuckFile) {
        BuckFile buckFile = (BuckFile) psiFile;
        BuckIdentifier[] identifiers =
            PsiTreeUtil.getChildrenOfType(buckFile, BuckIdentifier.class);
        for (BuckIdentifier identifier : identifiers) {
          if (identifier.getName().equals(key)) {
            result.add(identifier);
          }
        }
      }
    }
    return result;
  }

  /** Returns all known identifiers. */
  public static List<BuckIdentifier> findIdentifiers(Project project) {
    List<BuckIdentifier> result = new ArrayList<>();
    Collection<VirtualFile> virtualFiles =
        FileTypeIndex.getFiles(BuckFileType.INSTANCE, GlobalSearchScope.allScope(project));
    for (VirtualFile virtualFile : virtualFiles) {
      PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
      if (psiFile instanceof BuckFile) {
        BuckFile buckFile = (BuckFile) psiFile;
        BuckIdentifier[] identifiers =
            PsiTreeUtil.getChildrenOfType(buckFile, BuckIdentifier.class);
        if (identifiers != null) {
          Collections.addAll(result, identifiers);
        }
      }
    }
    return result;
  }
}
