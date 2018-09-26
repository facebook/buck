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

package com.facebook.buck.intellij.ideabuck.autodeps;

import java.util.Objects;

/**
 * Vastly simplified version of {@link com.facebook.buck.core.model.BuildTarget} for use in
 * syntactically resolving deps.
 */
class BuckTarget {
  private final String cellName;
  private final String relativePath;
  private final String ruleName;

  private static final BuckTarget EMPTY = new BuckTarget("", "", "");

  /** Parse a target relative to */
  static BuckTarget parse(String targetString) {
    return EMPTY.parseRelative(targetString);
  }

  private BuckTarget(String cellName, String relativePath, String ruleName) {
    this.cellName = cellName;
    this.relativePath = relativePath;
    this.ruleName = ruleName;
  }

  public BuckTarget parseRelative(String other) {
    if (other.isEmpty()) {
      return this;
    }
    String otherCellName;
    boolean usedDefaultCell;
    if (other.startsWith("//")) {
      otherCellName = cellName;
      usedDefaultCell = true;
      other = other.substring(2); // advance beyond '//'
    } else {
      String[] cellSplit = other.split("//", 2);
      if (cellSplit.length == 1) {
        otherCellName = cellName;
        usedDefaultCell = true;
        other = cellSplit[0];
      } else {
        otherCellName = cellSplit[0];
        usedDefaultCell = false;
        other = cellSplit[1];
      }
    }
    String otherRelativePath;
    String otherRuleName;
    if (other.startsWith(":")) {
      otherRelativePath = usedDefaultCell ? relativePath : "";
      otherRuleName = other.substring(1); // advance beyond ':'
    } else {
      String[] ruleSplit = other.split(":", 2);
      if (ruleSplit.length == 2) {
        otherRelativePath = ruleSplit[0];
        otherRuleName = ruleSplit[1];
      } else {
        otherRelativePath = ruleSplit[0];
        String[] pathSplit = otherRelativePath.split("/");
        otherRuleName = pathSplit[pathSplit.length - 1]; // repeat last part as
      }
    }
    return new BuckTarget(otherCellName, otherRelativePath, otherRuleName);
  }

  /** Returns a string representation of this target, relative to the given {@code other} target. */
  public String relativeTo(BuckTarget other) {
    if (!Objects.equals(cellName, other.cellName)) {
      return cellName + "//" + relativePath + ":" + ruleName;
    } else if (!relativePath.equals(other.relativePath)) {
      return "//" + relativePath + ":" + ruleName;
    } else {
      return ":" + ruleName;
    }
  }

  public String fullyQualified() {
    StringBuilder sb = new StringBuilder();
    if (cellName != null) {
      sb.append(cellName);
    }
    if (cellName != null || relativePath != null) {
      sb.append("//");
      if (relativePath != null) {
        sb.append(relativePath);
      }
    }
    if (ruleName != null) {
      sb.append(":").append(ruleName);
    }
    return sb.toString();
  }

  /** Returns the name of the cell. */
  public String getCellName() {
    return cellName;
  }

  /** Returns the relative path from the cell's root. */
  public String getRelativePath() {
    return relativePath;
  }

  /** Returns the rule name. */
  public String getRuleName() {
    return ruleName;
  }

  @Override
  public String toString() {
    return fullyQualified();
  }
}
