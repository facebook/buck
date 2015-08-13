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

package com.facebook.buck.intellij.plugin.actions.choosetargets;

import com.intellij.navigation.ItemPresentation;
import com.intellij.navigation.NavigationItem;
import icons.BuckIcons;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/**
 * Model for "Choose Target" action of Buck build tool window.
 */
public class ChooseTargetItem implements NavigationItem {

  private final String mAlias;
  private final String mTarget;
  private final BuckTargetItemPresentation mItemPresentation = new BuckTargetItemPresentation();

  public ChooseTargetItem(String target, @Nullable String alias) {
    mTarget = target;
    mAlias = alias;
  }

  public String getBuildTarget() {
    return mAlias == null ? mTarget : mAlias;
  }

  @Override
  public String getName() {
    return mAlias;
  }

  @Override
  public ItemPresentation getPresentation() {
    return mItemPresentation;
  }

  @Override
  public void navigate(boolean requestFocus) {
  }

  @Override
  public boolean canNavigate() {
    return false;
  }

  @Override
  public boolean canNavigateToSource() {
    return false;
  }

  private class BuckTargetItemPresentation implements ItemPresentation {
    @Override
    public String getPresentableText() {
      return mTarget;
    }

    @Override
    public String getLocationString() {
      return mAlias == null ? null : "(" + mAlias + ")";
    }

    @Override
    public Icon getIcon(boolean b) {
      return BuckIcons.FILE_TYPE;
    }
  }
}
