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

package com.facebook.buck.plugin.intellij.ui;

import com.facebook.buck.plugin.intellij.BuckTarget;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;
import javax.swing.tree.DefaultMutableTreeNode;

public class TargetNode extends DefaultMutableTreeNode {

  public enum Type {
    DIRECTORY,
    JAVA_LIBRARY,
    JAVA_BINARY,
    JAVA_TEST,
    OTHER
  }

  private final BuckTarget target;

  private final String name;
  private final Type type;

  public TargetNode(Type type, String name, @Nullable BuckTarget target) {
    super();
    this.name = Preconditions.checkNotNull(name);
    this.type = Preconditions.checkNotNull(type);
    this.target = target;
  }

  public Type getType() {
    return type;
  }

  public String getName() {
    return name;
  }

  public BuckTarget getTarget() {
    return target;
  }
}
