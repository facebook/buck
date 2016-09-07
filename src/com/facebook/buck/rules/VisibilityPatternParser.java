/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.model.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;

public class VisibilityPatternParser {
  private static final String VISIBILITY_PUBLIC = "PUBLIC";
  private static final String VISIBILITY_GROUP = "GROUP";

  private static final BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
      BuildTargetPatternParser.forVisibilityArgument();

  public VisibilityPattern parse(
      CellPathResolver cellNames,
      String buildTargetPattern) {
    if (VISIBILITY_PUBLIC.equals(buildTargetPattern)) {
      return PublicVisibilityPattern.INSTANCE;
    } else if (VISIBILITY_GROUP.equals(buildTargetPattern)) {
      return GroupVisibilityPattern.INSTANCE;
    } else {
      return new BuildTargetVisibilityPattern(buildTargetPatternParser.parse(
          cellNames,
          buildTargetPattern));
    }
  }

  @VisibleForTesting
  static class BuildTargetVisibilityPattern implements VisibilityPattern {
    private final BuildTargetPattern viewerPattern;

    public BuildTargetVisibilityPattern(BuildTargetPattern viewerPattern) {
      this.viewerPattern = viewerPattern;
    }

    // TODO(csarbora) let this account for specifying groups as targets in visibility too
    @Override
    public boolean checkVisibility(
        TargetGraph graphContext,
        TargetNode<?> viewer,
        TargetNode<?> viewed) {
      return viewerPattern.apply(viewer.getBuildTarget());
    }

    @Override
    public String getRepresentation() {
      return viewerPattern.getCellFreeRepresentation();
    }
  }

  private static class PublicVisibilityPattern implements VisibilityPattern {
    public static final PublicVisibilityPattern INSTANCE = new PublicVisibilityPattern();

    @Override
    public boolean checkVisibility(
        TargetGraph graphContext,
        TargetNode<?> viewer,
        TargetNode<?> viewed) {
      return true;
    }

    @Override
    public String getRepresentation() {
      return VISIBILITY_PUBLIC;
    }
  }

  // TODO(csarbora): warn if GROUP and not actually in a group
  private static class GroupVisibilityPattern implements VisibilityPattern {
    public static final GroupVisibilityPattern INSTANCE = new GroupVisibilityPattern();

    @Override
    public boolean checkVisibility(
        TargetGraph graphContext,
        TargetNode<?> viewer,
        TargetNode<?> viewed) {
      return !Sets.intersection(
          graphContext.getGroupsContainingTarget(viewer.getBuildTarget()),
          graphContext.getGroupsContainingTarget(viewed.getBuildTarget())).isEmpty();
    }

    @Override
    public String getRepresentation() {
      return VISIBILITY_GROUP;
    }
  }
}
