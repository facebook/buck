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

import com.google.common.collect.Lists;

import java.util.List;

public class XCScheme {
  private String name;
  private List<BuildActionEntry> buildAction;

  public XCScheme(String name) {
    this.name = name;
    this.buildAction = Lists.newArrayList();
  }

  public void addBuildAction(String containerRelativePath, String blueprintIdentifier) {
    this.buildAction.add(new BuildActionEntry(containerRelativePath, blueprintIdentifier));
  }

  public String getName() {
    return name;
  }

  public List<BuildActionEntry> getBuildAction() {
    return buildAction;
  }

  public class BuildActionEntry {
    private String containerRelativePath;
    private String blueprintIdentifier;

    public BuildActionEntry(String containerRelativePath, String blueprintIdentifier) {
      this.containerRelativePath = containerRelativePath;
      this.blueprintIdentifier = blueprintIdentifier;
    }

    public String getContainerRelativePath() {
      return containerRelativePath;
    }

    public String getBlueprintIdentifier() {
      return blueprintIdentifier;
    }
  }
}
