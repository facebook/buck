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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.folders.IJFolderFactory;
import com.facebook.buck.features.project.intellij.model.folders.IjFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjResourceFolderType;
import com.facebook.buck.features.project.intellij.model.folders.JavaResourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.JavaTestResourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.ResourceFolderFactory;
import com.facebook.buck.features.project.intellij.model.folders.SourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.TestFolder;
import com.google.common.base.Preconditions;
import javax.annotation.Nullable;

public enum FolderTypeWithPackageInfo {
  SOURCE_FOLDER_WITH_PACKAGE_INFO(
      true, false, SourceFolder.FACTORY, null, SourceFolder.class, null),
  SOURCE_FOLDER_WITHOUT_PACKAGE_INFO(
      false, false, SourceFolder.FACTORY, null, SourceFolder.class, null),
  TEST_FOLDER_WITH_PACKAGE_INFO(true, false, TestFolder.FACTORY, null, TestFolder.class, null),
  TEST_FOLDER_WITHOUT_PACKAGE_INFO(false, false, TestFolder.FACTORY, null, TestFolder.class, null),
  // The factories for resource and test resource folders should never be called from here. Instead,
  // they should be called using those provided in ResouceFolderType. Thus, we set them to null.
  JAVA_RESOURCE_FOLDER(
      false,
      true,
      null,
      JavaResourceFolder.FACTORY,
      JavaResourceFolder.class,
      IjResourceFolderType.JAVA_RESOURCE),
  JAVA_TEST_RESOURCE_FOLDER(
      false,
      true,
      null,
      JavaTestResourceFolder.FACTORY,
      JavaTestResourceFolder.class,
      IjResourceFolderType.JAVA_TEST_RESOURCE);

  private final boolean wantsPackagePrefix;
  private final boolean isResourceFolder;
  private final @Nullable IJFolderFactory folderFactory;
  private final @Nullable ResourceFolderFactory resourceFolderFactory;
  private final Class<? extends IjFolder> folderTypeClass;
  private final @Nullable IjResourceFolderType ijResourceFolderType;

  FolderTypeWithPackageInfo(
      boolean wantsPackagePrefix,
      boolean isResourceFolder,
      @Nullable IJFolderFactory folderFactory,
      @Nullable ResourceFolderFactory resourceFolderFactory,
      Class<? extends IjFolder> folderTypeClass,
      @Nullable IjResourceFolderType ijResourceFolderType) {
    this.wantsPackagePrefix = wantsPackagePrefix;
    this.isResourceFolder = isResourceFolder;
    this.folderFactory = folderFactory;
    this.resourceFolderFactory = resourceFolderFactory;
    this.folderTypeClass = folderTypeClass;
    this.ijResourceFolderType = ijResourceFolderType;
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
    } else if (folder instanceof JavaResourceFolder) {
      return JAVA_RESOURCE_FOLDER;
    } else if (folder instanceof JavaTestResourceFolder) {
      return JAVA_TEST_RESOURCE_FOLDER;
    } else {
      throw new IllegalArgumentException(String.format("Cannot detect folder type: %s", folder));
    }
  }

  public IJFolderFactory getFolderFactory() {
    return Preconditions.checkNotNull(folderFactory);
  }

  public Class<? extends IjFolder> getFolderTypeClass() {
    return folderTypeClass;
  }

  public boolean wantsPackagePrefix() {
    return wantsPackagePrefix;
  }

  public ResourceFolderFactory getResourceFolderFactory() {
    return Preconditions.checkNotNull(resourceFolderFactory);
  }

  public boolean isResourceFolder() {
    return isResourceFolder;
  }

  public IjResourceFolderType getIjResourceFolderType() {
    return Preconditions.checkNotNull(ijResourceFolderType);
  }
}
