/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij;

import com.facebook.buck.plugin.intellij.ui.TargetNode;
import com.google.common.base.Preconditions;

public class BuckTarget {

  private final String type;
  private final String name;
  private final String basePath;

  public BuckTarget(String type, String name, String basePath) {
    this.type = Preconditions.checkNotNull(type);
    this.name = Preconditions.checkNotNull(name);
    this.basePath = Preconditions.checkNotNull(basePath);
  }

  public String getName() {
    return name;
  }

  public String getFullName() {
    return String.format("//%s:%s", getBasePath(), getName());
  }

  @Override
  public String toString() {
    return getFullName();
  }

  public String getType() {
    return type;
  }

  public String getBasePath() {
    return basePath;
  }

  public TargetNode createTreeNode() {
    TargetNode.Type nodeType;
    if ("java_library".equals(type)) {
      nodeType = TargetNode.Type.JAVA_LIBRARY;
    } else if ("java_binary".equals(type)) {
      nodeType = TargetNode.Type.JAVA_BINARY;
    } else if ("java_test".equals(type)) {
      nodeType = TargetNode.Type.JAVA_TEST;
    } else if ("sh_test".equals(type)) {
      nodeType = TargetNode.Type.SH_TEST;
    } else {
      nodeType = TargetNode.Type.OTHER;
    }
    return new TargetNode(nodeType, getFullName(), this);
  }
}
