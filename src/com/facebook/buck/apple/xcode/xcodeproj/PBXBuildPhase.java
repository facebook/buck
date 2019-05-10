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

package com.facebook.buck.apple.xcode.xcodeproj;

import com.facebook.buck.apple.xcode.XcodeprojSerializer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Superclass of build phases. Each build phase represents one step in building a target. */
public abstract class PBXBuildPhase extends PBXProjectItem {
  private final List<PBXBuildFile> files;
  private Optional<Boolean> runOnlyForDeploymentPostprocessing = Optional.empty();
  private Optional<String> name = Optional.empty();

  public PBXBuildPhase() {
    this.files = new ArrayList<>();
  }

  public List<PBXBuildFile> getFiles() {
    return files;
  }

  public void setRunOnlyForDeploymentPostprocessing(Optional<Boolean> runOnlyForDeployment) {
    this.runOnlyForDeploymentPostprocessing = runOnlyForDeployment;
  }

  public void setName(Optional<String> name) {
    this.name = name;
  }

  public Optional<String> getName() {
    return name;
  }

  @Override
  public void serializeInto(XcodeprojSerializer s) {
    super.serializeInto(s);

    s.addField("files", files);
    name.ifPresent(phaseName -> s.addField("name", phaseName));
    runOnlyForDeploymentPostprocessing.ifPresent(
        runOnly -> s.addField("runOnlyForDeploymentPostprocessing", runOnly ? "1" : "0"));
  }
}
