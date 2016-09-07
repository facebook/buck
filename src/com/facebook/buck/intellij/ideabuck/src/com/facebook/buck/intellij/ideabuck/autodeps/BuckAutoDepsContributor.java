/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.actions.BuckAuditOwner;
import com.facebook.buck.intellij.ideabuck.config.BuckSettingsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.FutureCallback;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

public class BuckAutoDepsContributor implements PsiDocumentManager.Listener {
  private static final Logger LOG = Logger.getInstance(BuckAutoDepsContributor.class);
  private Project mProject;
  Pattern importPattern = Pattern.compile("import.*;");
  ObjectMapper mObjectMapper = new ObjectMapper();

  public BuckAutoDepsContributor(Project project) {
    mProject = project;
  }

  public String getImportedClassFromImportLine(String importClassLine) {
    return importClassLine.substring(
        importClassLine.indexOf(" ") + 1,
        importClassLine.length() - 1);
  }

  public Optional<VirtualFile> getVirtualFileFromImportLine(String importClassLine) {
    GlobalSearchScope scope = GlobalSearchScope.allScope(mProject);

    PsiClass psiClass = JavaPsiFacade.getInstance(mProject).
        findClass(getImportedClassFromImportLine(importClassLine), scope);

    return psiClass == null ?
        Optional.<VirtualFile>absent() :
        Optional.fromNullable(psiClass.getContainingFile().getVirtualFile());
  }

  @Override
  public void documentCreated(@NotNull final Document document, final PsiFile psiFile) {
    document.addDocumentListener(
        new DocumentListener() {
          @Override
          public void beforeDocumentChange(DocumentEvent documentEvent) {
          }

          @Override
          public void documentChanged(DocumentEvent documentEvent) {
            if (BuckSettingsProvider.getInstance().getState().enableAutoDeps) {
              String newLine = documentEvent.getNewFragment().toString();
              if (importPattern.matcher(newLine).matches()) {
                addDependency(
                    getVirtualFileFromImportLine(newLine),
                    Optional.fromNullable(FileDocumentManager.getInstance().getFile(document)));
              }
            }
          }
        });
  }

  @Override
  public void fileCreated(
      @NotNull PsiFile psiFile, @NotNull Document document) {

  }

  public void addDependency(
      final Optional<VirtualFile> importClass,
      final Optional<VirtualFile> currentClass) {
    if (!importClass.isPresent() || !currentClass.isPresent()) {
      return;
    }
    BuckAuditOwner.execute(
        mProject,
        new FutureCallback<String>() {
          @Override
          public void onSuccess(@Nullable String buckTargetResult) {
            try {
              String currentClassPath = currentClass.get().getPath();
              Map<String, List<String>> pathAndTargetData =
                  mObjectMapper.readValue(buckTargetResult, Map.class);
              String importTargetName = "";
              String importPath = "";
              String currentTargetName = "";
              String currentPath = "";

              for (Map.Entry<String, List<String>> targetAndPathEntry :
                  pathAndTargetData.entrySet()) {
                String[] pathAndTarget = targetAndPathEntry.getValue().
                    get(0).replaceFirst("//", "").split(":");
                if (currentClassPath.contains(targetAndPathEntry.getKey())) {
                  currentTargetName = pathAndTarget[1];
                  currentPath = pathAndTarget[0];
                } else {
                  importTargetName = pathAndTarget[1];
                  importPath = pathAndTarget[0];
                }
              }

              BuckDeps.addDeps(
                  mProject,
                  importPath,
                  currentPath,
                  importTargetName,
                  currentTargetName
              );
            } catch (IOException e) {
              LOG.error(e.toString());
            }
          }

          @Override
          public void onFailure(Throwable throwable) {
          }
        },
        importClass.get().getPath(),
        currentClass.get().getPath());
  }
}
