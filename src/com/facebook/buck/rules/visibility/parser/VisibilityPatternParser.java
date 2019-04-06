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
package com.facebook.buck.rules.visibility.parser;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPattern;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetPatternParser;
import com.facebook.buck.core.util.immutables.BuckStyleTuple;
import com.facebook.buck.rules.visibility.ObeysVisibility;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
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
  @JsonDeserialize
  abstract static class AbstractBuildTargetVisibilityPattern implements VisibilityPattern {

    @JsonProperty("pattern")
    abstract BuildTargetPattern getViewerPattern();

    @Override
    @JsonIgnore
    public boolean checkVisibility(ObeysVisibility viewer) {
      return getViewerPattern().matches(viewer.getBuildTarget());
    }

    @Override
    @JsonIgnore
    public String getRepresentation() {
      return getViewerPattern().getCellFreeRepresentation();
    }
  }

  @Value.Immutable
  @BuckStyleTuple
  @JsonDeserialize
  static class AbstractPublicVisibilityPattern implements VisibilityPattern {

    @Override
    @JsonIgnore
    public boolean checkVisibility(ObeysVisibility viewer) {
      return true;
    }

    @Override
    @JsonProperty("representation")
    public String getRepresentation() {
      return VISIBILITY_PUBLIC;
    }
  }
}
