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
  JAVA_RESOURCE("java-resource"),
  JAVA_TEST_RESOURCE("java-test-resource");

  private final String resourceType;

  ResourceFolderType(String resourceType) {
    this.resourceType = resourceType;
  }

  @Override
  public String toString() {
    return resourceType;
  }

  public IJFolderFactory getFactoryWithResourcesRoot(Path resourcesRoot) {
    if (this.equals(JAVA_RESOURCE)) {
      return JavaResourceFolder.getFactoryWithResourcesRoot(resourcesRoot);
    } else if (this.equals(JAVA_TEST_RESOURCE)) {
      return JavaTestResourceFolder.getFactoryWithResourcesRoot(resourcesRoot);
    } else {
      throw new IllegalArgumentException("Invalid resource type");
    }
  }

  public boolean isIjFolderInstance(IjFolder folder) {
    if (this.equals(JAVA_RESOURCE)) {
      return folder instanceof JavaResourceFolder;
    } else if (this.equals(JAVA_TEST_RESOURCE)) {
      return folder instanceof JavaTestResourceFolder;
    } else {
      return false;
    }
  }

  public Path getResourcesRootFromFolder(IjFolder folder) {
    if (this.equals(JAVA_RESOURCE)) {
      return ((JavaResourceFolder) folder).getResourcesRoot();
    } else if (this.equals(JAVA_TEST_RESOURCE)) {
      return ((JavaTestResourceFolder) folder).getResourcesRoot();
    } else {
      return null;
    }
  }
}
