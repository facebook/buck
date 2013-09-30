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

import com.facebook.buck.plugin.intellij.commands.event.Event;
import com.google.common.base.Preconditions;

import javax.annotation.Nullable;
import javax.swing.tree.DefaultMutableTreeNode;

public class ProgressNode extends DefaultMutableTreeNode {

  public enum Type {
    DIRECTORY,
    BUILDING,
    BUILT,
    BUILT_CACHED,
    BUILD_ERROR,
    TEST_CASE_SUCCESS,
    TEST_CASE_FAILURE,
    TEST_RESULT_SUCCESS,
    TEST_RESULT_FAILURE
  }

  private String name;
  private Type type;
  @Nullable
  private Event event;

  public ProgressNode(Type type, String name, @Nullable Event event) {
    super();
    this.name = Preconditions.checkNotNull(name);
    this.type = Preconditions.checkNotNull(type);
    this.event = event;
  }

  public Type getType() {
    return type;
  }

  public void setType(Type type) {
    this.type = Preconditions.checkNotNull(type);
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = Preconditions.checkNotNull(name);
  }

  public Event getEvent() {
    return event;
  }
}
