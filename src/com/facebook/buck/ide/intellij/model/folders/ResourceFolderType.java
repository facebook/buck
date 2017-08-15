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

package com.facebook.buck.ide.intellij.model.folders;

import java.nio.file.Path;

public enum ResourceFolderType {
  JAVA_RESOURCE("java-resource", JavaResourceFolder.class) {
    @Override
    public ResourceFolderFactory getFactory() {
      return JavaResourceFolder.getResourceFactory();
    }
  },
  JAVA_TEST_RESOURCE("java-test-resource", JavaTestResourceFolder.class) {
    @Override
    public ResourceFolderFactory getFactory() {
      return JavaTestResourceFolder.getResourceFactory();
    }
  };

  private final String resourceType;
  private final Class<? extends ResourceFolder> folderClass;

  ResourceFolderType(String resourceType, Class<? extends ResourceFolder> folderClass) {
    this.resourceType = resourceType;
    this.folderClass = folderClass;
  }

  @Override
  public String toString() {
    return resourceType;
  }

  public abstract ResourceFolderFactory getFactory();

  public boolean isIjFolderInstance(IjFolder folder) {
    return folderClass.isInstance(folder);
  }

  public Path getResourcesRootFromFolder(IjFolder folder) {
    return folderClass.cast(folder).getResourcesRoot();
  }
}
