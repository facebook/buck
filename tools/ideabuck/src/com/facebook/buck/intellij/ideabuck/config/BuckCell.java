/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.config;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/** Persistent per-cell preferences in a {@link com.intellij.openapi.project.Project}. */
public class BuckCell {
  public String name = "";
  public String root = "$PROJECT_DIR$";
  public String buildFileName = "BUCK";

  /** Returns a copy of this cell. */
  public BuckCell copy() {
    BuckCell result = new BuckCell();
    result.name = this.name;
    result.root = this.root;
    result.buildFileName = this.buildFileName;
    return result;
  }

  /**
   * Returns the name of this cells (as in the first component of targets formatted as {@code
   * cellname//path/to:target}).
   */
  public String getName() {
    return name;
  }

  /** Sets the name of this cell. */
  public void setName(String name) {
    this.name = Preconditions.checkNotNull(name);
  }

  /** Builder pattern for creating buck cells. */
  @VisibleForTesting
  public BuckCell withName(String name) {
    BuckCell copy = copy();
    copy.setName(name);
    return copy;
  }

  /**
   * Returns the root directory of this cell.
   *
   * <p>Note that the path may contain unexpanded {@link
   * com.intellij.openapi.application.PathMacros}, such as {@code $PROJECT_DIR$} or {@code
   * $USER_HOME$}.
   */
  public String getRoot() {
    return root;
  }

  /** Sets the root directory of this cell. */
  public void setRoot(String root) {
    this.root = Preconditions.checkNotNull(root);
  }

  /** Builder pattern for creating buck cells. */
  @VisibleForTesting
  public BuckCell withRoot(String root) {
    BuckCell copy = copy();
    copy.setRoot(root);
    return copy;
  }

  /**
   * The name of Buck files for this cell.
   *
   * @see {@url https://buckbuild.com/concept/buckconfig.html#buildfile.name The buck config setting
   *     for <code>buildfile.name</code>}
   */
  public String getBuildFileName() {
    return buildFileName;
  }

  /** Sets the name of Buck files for this cell. */
  public void setBuildFileName(String buildFileName) {
    this.buildFileName = Preconditions.checkNotNull(buildFileName);
  }

  /** Builder pattern for creating buck cells. */
  @VisibleForTesting
  public BuckCell withBuildFileName(String buildFileName) {
    BuckCell copy = copy();
    copy.setBuildFileName(buildFileName);
    return copy;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BuckCell buckCell = (BuckCell) o;
    return Objects.equal(name, buckCell.name)
        && Objects.equal(root, buckCell.root)
        && Objects.equal(buildFileName, buckCell.buildFileName);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name, root, buildFileName);
  }

  @Override
  public String toString() {
    return "BuckCell{"
        + "name='"
        + name
        + '\''
        + ", root='"
        + root
        + '\''
        + ", buildFileName='"
        + buildFileName
        + '\''
        + '}';
  }
}
