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
import com.facebook.buck.intellij.ideabuck.config.BuckProjectSettingsProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
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
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;
import org.jetbrains.annotations.NotNull;

public class BuckAutoDepsContributor implements PsiDocumentManager.Listener {
  private static final Logger LOG = Logger.getInstance(BuckAutoDepsContributor.class);
  private Project mProject;
  Pattern importPattern =
      Pattern.compile("import\\s+([a-z][A-Za-z0-9_]*(\\.[A-Za-z0-9]+)+)($|\\s|;)?");
  ObjectMapper mObjectMapper = new ObjectMapper();

  public BuckAutoDepsContributor(Project project) {
    mProject = project;
  }

  @Nullable
  public VirtualFile getVirtualFileFromClassname(String classname) {
    GlobalSearchScope scope = GlobalSearchScope.allScope(mProject);

    PsiClass psiClass = JavaPsiFacade.getInstance(mProject).findClass(classname, scope);
    if (psiClass == null) {
      return null;
    }
    PsiFile psiFile = psiClass.getContainingFile();
    if (psiFile == null) {
      return null;
    }
    return psiFile.getVirtualFile();
  }

  @Override
  public void documentCreated(@NotNull final Document document, final PsiFile psiFile) {
    document.addDocumentListener(
        new DocumentListener() {
          @Override
          public void beforeDocumentChange(DocumentEvent documentEvent) {}

          @Override
          public void documentChanged(DocumentEvent documentEvent) {
            if (!BuckProjectSettingsProvider.getInstance(mProject).isAutoDepsEnabled()) {
              return; // autodeps off, don't bother
            }
            VirtualFile documentFile = FileDocumentManager.getInstance().getFile(document);
            if (documentFile == null) {
              return; // Don't understand where this document is located
            }
            CharSequence fragment = documentEvent.getNewFragment();
            Matcher matcher = importPattern.matcher(fragment);
            while (matcher.find()) {
              String classname = matcher.group(1);
              VirtualFile classFile = getVirtualFileFromClassname(classname);
              if (classFile == null) {
                continue; // looks like an import, but we don't recognize where to get it
              }
              addDependency(classFile, documentFile);
            }
          }
        });
  }

  @Override
  public void fileCreated(@NotNull PsiFile psiFile, @NotNull Document document) {}

  public void addDependency(final VirtualFile importClass, final VirtualFile currentClass) {
    BuckAuditOwner.execute(
        mProject,
        buckTargetResult -> {
          try {
            String currentClassPath = currentClass.getPath();
            Map<String, List<String>> pathAndTargetData =
                mObjectMapper.readValue(buckTargetResult, Map.class);
            String importTargetName = "";
            String importPath = "";
            String currentTargetName = "";
            String currentPath = "";

            for (Map.Entry<String, List<String>> targetAndPathEntry :
                pathAndTargetData.entrySet()) {
              String[] pathAndTarget =
                  targetAndPathEntry.getValue().get(0).replaceFirst("//", "").split(":");
              if (currentClassPath.contains(targetAndPathEntry.getKey())) {
                currentTargetName = pathAndTarget[1];
                currentPath = pathAndTarget[0];
              } else {
                importTargetName = pathAndTarget[1];
                importPath = pathAndTarget[0];
              }
            }

            // Make sure we found both ends of the dependency to add.
            // TODO(ideabuck):  Consider if a single file is part of more than one target.
            if (!Strings.isNullOrEmpty(currentTargetName)
                && !Strings.isNullOrEmpty(currentPath)
                && !Strings.isNullOrEmpty(importTargetName)
                && !Strings.isNullOrEmpty(importPath)) {
              BuckDeps.addDeps(
                  mProject, importPath, currentPath, importTargetName, currentTargetName);
              BuckDeps.addVisibility(
                  mProject, importPath, currentPath, importTargetName, currentTargetName);
            }
          } catch (IOException e) {
            LOG.error(e.toString());
          }
        },
        importClass.getPath(),
        currentClass.getPath());
  }
}
