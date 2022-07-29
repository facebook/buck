/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.intellij.ideabuck.lang;

import com.intellij.lang.Language;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.psi.SingleRootFileViewProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class BuckFileViewProvider extends SingleRootFileViewProvider {

  public BuckFileViewProvider(@NotNull PsiManager manager, @NotNull VirtualFile file) {
    super(manager, file, true, BuckLanguage.INSTANCE);
  }

  @Override
  protected @Nullable PsiFile createFile(@NotNull Language lang) {
    return new BuckFile(this);
  }

  @Override
  protected @NotNull PsiFile createFile(
      @NotNull VirtualFile file, @NotNull FileType fileType, @NotNull Language language) {
    return new BuckFile(this);
  }
}
