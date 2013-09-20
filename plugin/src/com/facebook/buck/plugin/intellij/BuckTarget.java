package com.facebook.buck.plugin.intellij;

import com.google.common.base.Preconditions;

public class BuckTarget {
  private final String name;
  private String description;

  public BuckTarget(String name) {
    this.name = Preconditions.checkNotNull(name);
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public String toString() {
    return name;
  }
}
