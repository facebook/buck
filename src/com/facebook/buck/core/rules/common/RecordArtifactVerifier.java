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

package com.facebook.buck.core.rules.common;

import com.facebook.buck.core.build.buildable.context.BuildableContext;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * RecordArtifactVerifier can be used to ease the migration to ModernBuildRule. It will record a
 * fixed set of artifacts and then act as a BuildableContext and verify that any further recorded
 * artifacts are in the initial set (or children of an item in the set). This can be used at the
 * top-level getBuildSteps() call to replace the BuildableContext.
 */
public class RecordArtifactVerifier implements BuildableContext {
  private final List<Path> recordedPaths = new ArrayList<>();
  private final ImmutableSet<Path> allowedPaths;

  public RecordArtifactVerifier(Collection<Path> allowedPaths, BuildableContext context) {
    this.allowedPaths = ImmutableSet.copyOf(allowedPaths);
    allowedPaths.forEach(context::recordArtifact);
  }

  @Override
  public void recordArtifact(Path pathToArtifact) {
    for (Path root : allowedPaths) {
      if (pathToArtifact.startsWith(root) && pathToArtifact.normalize().startsWith(root)) {
        recordedPaths.add(pathToArtifact);
        return;
      }
    }
    throw new RuntimeException(String.format("Recorded path %s is not allowed.", pathToArtifact));
  }

  public Iterable<Path> getPaths() {
    return recordedPaths;
  }
}
