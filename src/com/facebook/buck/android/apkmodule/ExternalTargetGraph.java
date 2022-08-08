/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android.apkmodule;

import com.facebook.buck.core.model.HasBuildTargetAndBuildDeps;
import com.facebook.buck.core.model.TargetGraphInterface;
import com.google.common.collect.ImmutableMap;
import java.util.Set;

/**
 * A "target graph" that is constructed based on an external graph, that can then be used to create
 * an {@link APKModuleGraph}
 */
class ExternalTargetGraph implements TargetGraphInterface<ExternalTargetGraph.ExternalBuildTarget> {
  private final ImmutableMap<ExternalBuildTarget, ExternalTargetNode> map;
  private final ImmutableMap<String, ExternalBuildTarget> nameToBuildTargetMap;

  public ExternalTargetGraph(
      ImmutableMap<ExternalBuildTarget, ExternalTargetNode> map,
      ImmutableMap<String, ExternalBuildTarget> nameToBuildTargetMap) {
    this.map = map;
    this.nameToBuildTargetMap = nameToBuildTargetMap;
  }

  ExternalBuildTarget getBuildTarget(String buildTargetName) {
    return nameToBuildTargetMap.get(buildTargetName);
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public HasBuildTargetAndBuildDeps<ExternalBuildTarget> get(ExternalBuildTarget buildTarget) {
    return map.get(buildTarget);
  }

  /** A node in an external target graph. */
  static class ExternalTargetNode implements HasBuildTargetAndBuildDeps<ExternalBuildTarget> {
    private final ExternalBuildTarget buildTarget;
    private final Set<ExternalBuildTarget> buildDeps;

    public ExternalTargetNode(ExternalBuildTarget buildTarget, Set<ExternalBuildTarget> buildDeps) {
      this.buildTarget = buildTarget;
      this.buildDeps = buildDeps;
    }

    @Override
    public ExternalBuildTarget getBuildTarget() {
      return buildTarget;
    }

    @Override
    public Set<ExternalBuildTarget> getBuildDeps() {
      return buildDeps;
    }
  }

  /** A representation of a build target that just uses the build target's name */
  static class ExternalBuildTarget implements Comparable<ExternalBuildTarget> {
    private final String name;

    public ExternalBuildTarget(String name) {
      this.name = name;
    }

    @Override
    public int compareTo(ExternalBuildTarget o) {
      return this.name.compareTo(o.name);
    }

    String getName() {
      return name;
    }
  }
}
