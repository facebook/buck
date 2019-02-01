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

package com.facebook.buck.intellij.ideabuck.lang;

import com.intellij.extapi.psi.PsiFileBase;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.psi.FileViewProvider;
import javax.swing.Icon;

/**
 * File that is part of the {@code .buckconfig} family of config files.
 *
 * <p>This includes files imported using the syntax: {@code <file:path-to-included-file>} plus the
 * files at locations listed in: {@url https://buckbuild.com/files-and-dirs/buckconfig.html}.
 */
public class BcfgFile extends PsiFileBase {

  public BcfgFile(FileViewProvider viewProvider) {
    super(viewProvider, BcfgLanguage.INSTANCE);
  }

  @Override
  public FileType getFileType() {
    return BcfgFileType.INSTANCE;
  }

  @Override
  public String toString() {
    return "Buckconfig File";
  }

  @Override
  public Icon getIcon(int flags) {
    return super.getIcon(flags);
  }
}
