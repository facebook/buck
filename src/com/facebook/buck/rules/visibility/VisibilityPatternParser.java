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
package com.facebook.buck.rules.visibility;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.parser.BuildTargetPattern;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.google.common.annotations.VisibleForTesting;
import org.immutables.value.Value;

public class VisibilityPatternParser {
  public static final String VISIBILITY_PUBLIC = "PUBLIC";

  private static final BuildTargetPatternParser<BuildTargetPattern> buildTargetPatternParser =
      BuildTargetPatternParser.forVisibilityArgument();

  public static VisibilityPattern parse(CellPathResolver cellNames, String buildTargetPattern) {
    if (VISIBILITY_PUBLIC.equals(buildTargetPattern)) {
      return PublicVisibilityPattern.of();
    } else {
      return BuildTargetVisibilityPattern.of(
          buildTargetPatternParser.parse(cellNames, buildTargetPattern));
    }
  }

  @Value.Immutable
  @BuckStyleTuple
  @VisibleForTesting
  abstract static class AbstractBuildTargetVisibilityPattern implements VisibilityPattern {

    abstract BuildTargetPattern getViewerPattern();

    @Override
    public boolean checkVisibility(ObeysVisibility viewer) {
      return getViewerPattern().matches(viewer.getBuildTarget());
    }

    @Override
    public String getRepresentation() {
      return getViewerPattern().getCellFreeRepresentation();
    }
  }

  @Value.Immutable
  @BuckStyleTuple
  static class AbstractPublicVisibilityPattern implements VisibilityPattern {

    @Override
    public boolean checkVisibility(ObeysVisibility viewer) {
      return true;
    }

    @Override
    public String getRepresentation() {
      return VISIBILITY_PUBLIC;
    }
  }
}
