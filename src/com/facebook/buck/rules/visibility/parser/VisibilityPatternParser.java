/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.rules.visibility.parser;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcher;
import com.facebook.buck.core.parser.buildtargetparser.BuildTargetMatcherParser;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.rules.visibility.ObeysVisibility;
import com.facebook.buck.rules.visibility.VisibilityPattern;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.google.common.annotations.VisibleForTesting;
import java.nio.file.Path;

public class VisibilityPatternParser {
  public static final String VISIBILITY_PUBLIC = "PUBLIC";

  private static final BuildTargetMatcherParser<BuildTargetMatcher> buildTargetPatternParser =
      BuildTargetMatcherParser.forVisibilityArgument();

  /**
   * @param cellNames Known cell names in the workspace, used to resolve which cell the {@param
   *     buildTargetPattern} belongs.
   * @param definingPath Path of the file defining the {@param buildTargetPattern}.
   * @param buildTargetPattern The build target pattern to parse.
   * @return
   */
  public static VisibilityPattern parse(
      CellPathResolver cellNames, Path definingPath, String buildTargetPattern) {
    if (VISIBILITY_PUBLIC.equals(buildTargetPattern)) {
      return ImmutablePublicVisibilityPattern.of(definingPath);
    } else {
      return ImmutableBuildTargetVisibilityPattern.of(
          buildTargetPatternParser.parse(buildTargetPattern, cellNames.getCellNameResolver()),
          definingPath);
    }
  }

  @BuckStyleValue
  @VisibleForTesting
  @JsonDeserialize
  abstract static class BuildTargetVisibilityPattern implements VisibilityPattern {

    @JsonProperty("pattern")
    abstract BuildTargetMatcher getViewerPattern();

    @Override
    @JsonProperty("definingPath")
    public abstract Path getDefiningPath();

    @Override
    @JsonIgnore
    public boolean checkVisibility(ObeysVisibility viewer) {
      return getViewerPattern().matches(viewer.getBuildTarget().getUnconfiguredBuildTarget());
    }

    @Override
    @JsonIgnore
    public String getRepresentation() {
      return getViewerPattern().getCellFreeRepresentation();
    }
  }

  @BuckStyleValue
  @JsonDeserialize
  abstract static class PublicVisibilityPattern implements VisibilityPattern {

    @Override
    @JsonProperty("definingPath")
    public abstract Path getDefiningPath();

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
