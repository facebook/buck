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
package com.facebook.buck.intellij.ideabuck.annotator;

import com.facebook.buck.intellij.ideabuck.highlight.BuckSyntaxHighlighter;
import com.facebook.buck.intellij.ideabuck.lang.psi.BcfgInline;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.psi.PsiElement;
import java.nio.file.Path;
import java.util.Optional;
import org.jetbrains.annotations.NotNull;

/** Annotates {@code .buckconfig} files. */
public class BcfgAnnotator implements Annotator {

  @Override
  public void annotate(@NotNull PsiElement psiElement, @NotNull AnnotationHolder holder) {
    if (psiElement instanceof BcfgInline) {
      annotateInlineFile((BcfgInline) psiElement, holder);
    }
  }

  private void annotateInlineFile(
      @NotNull BcfgInline bcfgInline, @NotNull AnnotationHolder holder) {
    Optional<Path> path = bcfgInline.getPath();
    if (!path.isPresent()) {
      holder.createErrorAnnotation(
          bcfgInline.getFilePath(),
          "Cannot parse " + bcfgInline.getFilePath().getText() + " as a file path.");
    } else if (path.get().toFile().exists()) {
      holder
          .createInfoAnnotation(bcfgInline.getFilePath(), path.get().toString())
          .setTextAttributes(BuckSyntaxHighlighter.BUCK_FILE_NAME);
    } else {
      if (bcfgInline.isRequired()) {
        holder
            .createErrorAnnotation(bcfgInline.getFilePath(), "Required file missing")
            .setTextAttributes(BuckSyntaxHighlighter.BUCK_INVALID_TARGET);
      } else {
        holder.createInfoAnnotation(bcfgInline.getFilePath(), "Optional file missing");
      }
    }
  }
}
