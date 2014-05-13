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

package com.facebook.buck.apple.xcode;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.util.EnumSet;
import java.util.List;

public class XCScheme {
  private String name;
  private List<BuildActionEntry> buildAction;

  public XCScheme(String name) {
    this.name = name;
    this.buildAction = Lists.newArrayList();
  }

  public void addBuildAction(BuildActionEntry entry) {
    this.buildAction.add(entry);
  }

  public String getName() {
    return name;
  }

  public List<BuildActionEntry> getBuildAction() {
    return buildAction;
  }

  public static class BuildActionEntry {
    enum BuildFor {
      RUNNING,
      TESTING,
      PROFILING,
      ARCHIVING,
      ANALYZING;

      public static final EnumSet<BuildFor> DEFAULT = EnumSet.allOf(BuildFor.class);
      public static final EnumSet<BuildFor> TEST_ONLY = EnumSet.of(TESTING, ANALYZING);
    }

    private String containerRelativePath;
    private String blueprintIdentifier;
    private final EnumSet<BuildFor> buildFor;

    public BuildActionEntry(
        String containerRelativePath,
        String blueprintIdentifier,
        EnumSet<BuildFor> buildFor) {
      this.containerRelativePath = containerRelativePath;
      this.blueprintIdentifier = blueprintIdentifier;
      this.buildFor = Preconditions.checkNotNull(buildFor);
    }

    public String getContainerRelativePath() {
      return containerRelativePath;
    }

    public String getBlueprintIdentifier() {
      return blueprintIdentifier;
    }

    public EnumSet<BuildFor> getBuildFor() {
      return buildFor;
    }
  }
}
