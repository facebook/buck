/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildFileTree;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.util.MorePaths;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.collect.ImmutableSet;

import java.io.File;
import java.util.Map;

/**
 * Factory for creating a {@link BuildRuleFactoryParams} that does not check whether the file
 * paths it creates actually exist. Designed to be used exclusively for testing.
 */
public final class NonCheckingBuildRuleFactoryParams {

  private NonCheckingBuildRuleFactoryParams() {}

  public static BuildRuleFactoryParams createNonCheckingBuildRuleFactoryParams(
      Map<String, ?> instance,
      BuildTargetParser buildTargetParser,
      BuildTarget target) {
    return new BuildRuleFactoryParams(
        instance,
        new ProjectFilesystem(new File(".")),
        new NonCheckingBuildFileTree(),
        buildTargetParser,
        target,
        new FakeRuleKeyBuilderFactory(),
        /* ignoreFileExistenceChecks */ true);
  }

  private static class NonCheckingBuildFileTree extends BuildFileTree {

    public NonCheckingBuildFileTree() {
      super(ImmutableSet.<BuildTarget>of());
    }

    @Override
    public Iterable<String> getChildPaths(BuildTarget buildTarget) {
      return ImmutableSet.of();
    }

    @Override
    public String getBasePathOfAncestorTarget(String filePath) {
      // Always assume the file is local to the target.
      return MorePaths.newPathInstance(filePath).getParent().toString();
    }
  }

}
