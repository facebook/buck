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

import com.facebook.buck.intellij.ideabuck.build.BuckBuildUtil;
import com.facebook.buck.intellij.ideabuck.lang.BuckFile;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckPsiUtils;
import com.facebook.buck.intellij.ideabuck.lang.psi.BuckTypes;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RawText;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiDirectory;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.TokenType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiUtilCore;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;

public class BuckCopyPasteProcessor implements CopyPastePreProcessor {

  private static final Pattern UNSOLVED_DEPENDENCY_PATTERN =
      Pattern.compile("^(package|import)\\s*([\\w\\.]*);?\\s*$");
  private static final Pattern SOLVED_DEPENDENCY_PATTERN =
      Pattern.compile("^\\s*[\\w/]*:[\\w]+\\s*$");

  @Nullable
  @Override
  public String preprocessOnCopy(PsiFile psiFile, int[] ints, int[] ints1, String s) {
    return null;
  }

  @Override
  public String preprocessOnPaste(
      Project project, PsiFile psiFile, Editor editor, String text, RawText rawText) {
    if (!(psiFile instanceof BuckFile)) {
      return text;
    }
    final Document document = editor.getDocument();
    PsiDocumentManager.getInstance(project).commitDocument(document);
    final SelectionModel selectionModel = editor.getSelectionModel();

    // Pastes in block selection mode (column mode) are not handled by a CopyPasteProcessor.
    final int selectionStart = selectionModel.getSelectionStart();
    final PsiElement element = psiFile.findElementAt(selectionStart);
    if (element == null) {
      return text;
    }

    if (BuckPsiUtils.hasElementType(
        element.getNode(),
        TokenType.WHITE_SPACE,
        BuckTypes.SINGLE_QUOTED_STRING,
        BuckTypes.DOUBLE_QUOTED_STRING)) {
      PsiElement property = BuckPsiUtils.findAncestorWithType(element, BuckTypes.PROPERTY);
      if (checkPropertyName(property)) {
        return formatPasteText(text, element, project);
      }
    }
    return text;
  }

  protected boolean checkPropertyName(PsiElement property) {
    if (property == null) {
      return false;
    }
    PsiElement leftValue = property.getFirstChild();
    if (leftValue == null || leftValue.getNode().getElementType() != BuckTypes.PROPERTY_LVALUE) {
      return false;
    }
    leftValue = leftValue.getFirstChild();
    if (leftValue == null || leftValue.getNode().getElementType() != BuckTypes.IDENTIFIER) {
      return false;
    }
    if (leftValue.getText().equals("deps") || leftValue.getText().equals("visibility")) {
      return true;
    }
    return false;
  }

  /**
   * Automatically convert to buck dependency pattern Example 1: "import
   * com.example.activity.MyFirstActivity" -> "//java/com/example/activity:activity"
   *
   * <p>Example 2: "package com.example.activity;" -> "//java/com/example/activity:activity"
   *
   * <p>Example 3: "com.example.activity.MyFirstActivity" -> "//java/com/example/activity:activity"
   *
   * <p>Example 4: "/Users/tim/tb/java/com/example/activity/BUCK" ->
   * "//java/com/example/activity:activity"
   *
   * <p>Example 5 //apps/myapp:app -> "//apps/myapp:app",
   */
  private String formatPasteText(String text, PsiElement element, Project project) {
    Iterable<String> paths = Splitter.on('\n').trimResults().omitEmptyStrings().split(text);
    List<String> results = new ArrayList<>();
    for (String path : paths) {
      Matcher matcher = UNSOLVED_DEPENDENCY_PATTERN.matcher(path);
      if (matcher.matches()) {
        results.add(resolveUnsolvedBuckDependency(element, project, matcher.group(2)));
      } else if (SOLVED_DEPENDENCY_PATTERN.matcher(path).matches()) {
        results.add(buildSolvedBuckDependency(path));
      } else {
        // Any non-target results in no formatting
        return text;
      }
    }
    return Joiner.on('\n').skipNulls().join(results);
  }

  private String buildSolvedBuckDependency(String path) {
    StringBuilder stringBuilder = new StringBuilder();
    stringBuilder.append('"');
    if (!(path.startsWith("//") || path.startsWith(":"))) {
      if (path.startsWith("/")) {
        stringBuilder.append('/');
      } else {
        stringBuilder.append("//");
      }
    }
    return stringBuilder.append(path).append("\",").toString();
  }

  private String resolveUnsolvedBuckDependency(PsiElement element, Project project, String path) {
    String original = path;
    VirtualFile buckFile = referenceNameToBuckFile(project, path);
    if (buckFile != null) {
      path = buckFile.getPath().replaceFirst(project.getBasePath(), "");
      path = "/" + path.replace('.', '/');
      path = path.substring(0, path.lastIndexOf("/"));

      String target = BuckBuildUtil.extractBuckTarget(project, buckFile);
      if (target != null) {
        path += target;
      } else {
        String lastWord = path.substring(path.lastIndexOf("/") + 1, path.length());
        path += ":" + lastWord;
      }
      if (element.getNode().getElementType() == TokenType.WHITE_SPACE) {
        path = "\"" + path + "\",";
      }
      return path;
    } else {
      return original;
    }
  }

  private VirtualFile referenceNameToBuckFile(Project project, String reference) {
    // First test if it is a absolute path of a file.
    File tryFile = new File(reference);
    if (tryFile != null) {
      VirtualFile file = VfsUtil.findFileByIoFile(tryFile, true);
      if (file != null) {
        return BuckBuildUtil.getBuckFileFromDirectory(file.getParent());
      }
    }

    // Try class firstly.
    PsiClass classElement =
        JavaPsiFacade.getInstance(project)
            .findClass(reference, GlobalSearchScope.allScope(project));
    if (classElement != null) {
      VirtualFile file = PsiUtilCore.getVirtualFile(classElement);
      return BuckBuildUtil.getBuckFileFromDirectory(file.getParent());
    }

    // Then try package.
    PsiPackage packageElement = JavaPsiFacade.getInstance(project).findPackage(reference);
    if (packageElement != null) {
      PsiDirectory directory = packageElement.getDirectories()[0];
      return BuckBuildUtil.getBuckFileFromDirectory(directory.getVirtualFile());
    }

    // Extract the package from the reference.
    int index = reference.lastIndexOf(".");
    if (index == -1) {
      return null;
    }
    reference = reference.substring(0, index);

    // Try to find the package again.
    packageElement = JavaPsiFacade.getInstance(project).findPackage(reference);
    if (packageElement != null) {
      PsiDirectory directory = packageElement.getDirectories()[0];
      return BuckBuildUtil.getBuckFileFromDirectory(directory.getVirtualFile());
    }
    return null;
  }
}
