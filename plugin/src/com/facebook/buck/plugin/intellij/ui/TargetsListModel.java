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
