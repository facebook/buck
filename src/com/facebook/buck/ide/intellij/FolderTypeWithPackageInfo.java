/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.ide.intellij.model.folders.IJFolderFactory;
import com.facebook.buck.ide.intellij.model.folders.IjFolder;
import com.facebook.buck.ide.intellij.model.folders.SourceFolder;
import com.facebook.buck.ide.intellij.model.folders.TestFolder;

public enum FolderTypeWithPackageInfo {
  SOURCE_FOLDER_WITH_PACKAGE_INFO(true, SourceFolder.FACTORY, SourceFolder.class),
  SOURCE_FOLDER_WITHOUT_PACKAGE_INFO(false, SourceFolder.FACTORY, SourceFolder.class),
  TEST_FOLDER_WITH_PACKAGE_INFO(true, TestFolder.FACTORY, TestFolder.class),
  TEST_FOLDER_WITHOUT_PACKAGE_INFO(false, TestFolder.FACTORY, TestFolder.class);

  public static final int FOLDER_TYPES_COUNT = values().length;

  private final boolean wantsPackagePrefix;
  private final IJFolderFactory folderFactory;
  private final Class<? extends IjFolder> folderTypeClass;

  FolderTypeWithPackageInfo(
      boolean wantsPackagePrefix,
      IJFolderFactory folderFactory,
      Class<? extends IjFolder> folderTypeClass) {
    this.wantsPackagePrefix = wantsPackagePrefix;
    this.folderFactory = folderFactory;
    this.folderTypeClass = folderTypeClass;
  }

  public static FolderTypeWithPackageInfo fromFolder(IjFolder folder) {
    if (folder instanceof SourceFolder) {
      return folder.getWantsPackagePrefix()
          ? SOURCE_FOLDER_WITH_PACKAGE_INFO
          : SOURCE_FOLDER_WITHOUT_PACKAGE_INFO;
    } else if (folder instanceof TestFolder) {
      return folder.getWantsPackagePrefix()
          ? TEST_FOLDER_WITH_PACKAGE_INFO
          : TEST_FOLDER_WITHOUT_PACKAGE_INFO;
    } else {
      throw new IllegalArgumentException(String.format("Cannot detect folder type: %s", folder));
    }
  }

  public IJFolderFactory getFolderFactory() {
    return folderFactory;
  }

  public Class<? extends IjFolder> getFolderTypeClass() {
    return folderTypeClass;
  }

  public boolean wantsPackagePrefix() {
    return wantsPackagePrefix;
  }
}
