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
import com.google.common.collect.ImmutableList;

import javax.swing.AbstractListModel;

public class TargetsListModel extends AbstractListModel {

  private ImmutableList<BuckTarget> targets;

  public TargetsListModel(ImmutableList<BuckTarget> targets) {
    Preconditions.checkNotNull(targets);
    setTargets(targets);
  }

  public void setTargets(ImmutableList<BuckTarget> targets) {
    Preconditions.checkNotNull(targets);
    this.targets = ImmutableList.copyOf(targets);
    fireContentsChanged(this, 0 /* start index */ , this.targets.size() + 1 /* end index */);
  }

  @Override
  public int getSize() {
    return targets.size();
  }

  @Override
  public Object getElementAt(int index) {
    return targets.get(index);
  }
}
