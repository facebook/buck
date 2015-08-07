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

package com.facebook.buck.intellij.plugin.lang;

import com.facebook.buck.intellij.plugin.file.BuckFileType;
import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;

import javax.swing.Icon;

public class BuckFile extends PsiFileBase {

  public BuckFile(FileViewProvider viewProvider) {
    super(viewProvider, BuckLanguage.INSTANCE);
  }

  @Override
  public FileType getFileType() {
    return BuckFileType.INSTANCE;
  }

  @Override
  public String toString() {
    return "Buck File";
  }

  @Override
  public Icon getIcon(int flags) {
    return super.getIcon(flags);
  }
}
