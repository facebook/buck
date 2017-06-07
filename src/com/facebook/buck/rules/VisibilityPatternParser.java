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

public class VisibilityPatternParser {
  public static final String VISIBILITY_PUBLIC = "PUBLIC";

  private static final BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
      BuildTargetPatternParser.forVisibilityArgument();

  public VisibilityPattern parse(CellPathResolver cellNames, String buildTargetPattern) {
    if (VISIBILITY_PUBLIC.equals(buildTargetPattern)) {
      return PublicVisibilityPattern.INSTANCE;
    } else {
      return new BuildTargetVisibilityPattern(
          buildTargetPatternParser.parse(cellNames, buildTargetPattern));
    }
  }

  @VisibleForTesting
  static class BuildTargetVisibilityPattern implements VisibilityPattern {
    private final BuildTargetPattern viewerPattern;

    public BuildTargetVisibilityPattern(BuildTargetPattern viewerPattern) {
      this.viewerPattern = viewerPattern;
    }

    @Override
    public boolean checkVisibility(ObeysVisibility viewer, ObeysVisibility viewed) {
      return viewerPattern.matches(viewer.getBuildTarget());
    }

    @Override
    public String getRepresentation() {
      return viewerPattern.getCellFreeRepresentation();
    }
  }

  private static class PublicVisibilityPattern implements VisibilityPattern {
    public static final PublicVisibilityPattern INSTANCE = new PublicVisibilityPattern();

    @Override
    public boolean checkVisibility(ObeysVisibility viewer, ObeysVisibility viewed) {
      return true;
    }

    @Override
    public String getRepresentation() {
      return VISIBILITY_PUBLIC;
    }
  }
}
