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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.UnknownCellException;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.google.common.base.Preconditions;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Context for parsing build target names. Fully-qualified target names are parsed the same
 * regardless of the context.
 */
public abstract class BuildTargetPatternParser<T> {

  private static final String BUILD_RULE_PREFIX = "//";
  private static final String WILDCARD_BUILD_RULE_SUFFIX = "...";
  private static final String BUILD_RULE_SEPARATOR = ":";

  private final UnconfiguredBuildTargetFactory unconfiguredBuildTargetFactory =
      new ParsingUnconfiguredBuildTargetFactory();

  /**
   * 1. //src/com/facebook/buck/cli:cli will be converted to a single build target 2.
   * //src/com/facebook/buck/cli: will match all in the same directory. 3.
   * //src/com/facebook/buck/cli/... will match all in or under that directory. For case 2 and 3,
   * parseContext is expected to be {@link BuildTargetPatternParser#forVisibilityArgument()}.
   */
  public final T parse(CellPathResolver cellNames, String buildTargetPattern) {
    Preconditions.checkArgument(
        buildTargetPattern.contains(BUILD_RULE_PREFIX),
        "'%s' must start with '//' or a cell followed by '//'",
        buildTargetPattern);

    if (buildTargetPattern.endsWith("/" + WILDCARD_BUILD_RULE_SUFFIX)) {
      return createWildCardPattern(cellNames, buildTargetPattern);
    }

    UnconfiguredBuildTarget target =
        unconfiguredBuildTargetFactory.createWithWildcard(cellNames, buildTargetPattern);
    if (target.getShortNameAndFlavorPostfix().isEmpty()) {
      return createForChildren(target.getCellPath(), target.getBasePath());
    } else {
      return createForSingleton(target);
    }
  }

  private T createWildCardPattern(CellPathResolver cellNames, String buildTargetPatternWithCell) {
    Path cellPath;
    String buildTargetPattern;
    int index = buildTargetPatternWithCell.indexOf(BUILD_RULE_PREFIX);
    if (index > 0) {
      try {
        cellPath =
            cellNames.getCellPathOrThrow(
                Optional.of(buildTargetPatternWithCell.substring(0, index)));
      } catch (UnknownCellException e) {
        throw new BuildTargetParseException(
            String.format(
                "When parsing %s: %s",
                buildTargetPatternWithCell, e.getHumanReadableErrorMessage()));
      }
      buildTargetPattern = buildTargetPatternWithCell.substring(index);
    } else {
      cellPath = cellNames.getCellPathOrThrow(Optional.empty());
      buildTargetPattern = buildTargetPatternWithCell;
    }

    if (buildTargetPattern.contains(BUILD_RULE_SEPARATOR)) {
      throw new BuildTargetParseException(
          String.format("'%s' cannot contain colon", buildTargetPattern));
    }

    if (!buildTargetPattern.equals(BUILD_RULE_PREFIX + WILDCARD_BUILD_RULE_SUFFIX)) {
      String basePathWithPrefix =
          buildTargetPattern.substring(
              0, buildTargetPattern.length() - WILDCARD_BUILD_RULE_SUFFIX.length() - 1);
      BuildTargetParser.checkBaseName(basePathWithPrefix, buildTargetPattern);
    }

    String basePathWithSlash =
        buildTargetPattern.substring(
            BUILD_RULE_PREFIX.length(),
            buildTargetPattern.length() - WILDCARD_BUILD_RULE_SUFFIX.length());
    // Make sure the basePath comes from the same underlying filesystem.
    Path basePath = cellPath.getFileSystem().getPath(basePathWithSlash);
    return createForDescendants(cellPath, basePath);
  }

  /** Used when parsing target names in the {@code visibility} argument to a build rule. */
  public static BuildTargetPatternParser<BuildTargetPattern> forVisibilityArgument() {
    return new VisibilityContext();
  }

  /**
   * @return description of the target name and context being parsed when an error was encountered.
   *     Examples are ":azzetz in build file //first-party/orca/orcaapp/BUCK" and
   *     "//first-party/orca/orcaapp:mezzenger in context FULLY_QUALIFIED"
   */
  protected abstract T createForDescendants(Path cellPath, Path basePath);

  protected abstract T createForChildren(Path cellPath, Path basePath);

  protected abstract T createForSingleton(UnconfiguredBuildTarget target);

  private static class VisibilityContext extends BuildTargetPatternParser<BuildTargetPattern> {

    @Override
    public BuildTargetPattern createForDescendants(Path cellPath, Path basePath) {
      return SubdirectoryBuildTargetPattern.of(cellPath, basePath);
    }

    @Override
    public BuildTargetPattern createForChildren(Path cellPath, Path basePath) {
      return ImmediateDirectoryBuildTargetPattern.of(cellPath, basePath);
    }

    @Override
    public BuildTargetPattern createForSingleton(UnconfiguredBuildTarget target) {
      return SingletonBuildTargetPattern.of(
          target.getUnflavoredBuildTarget().getCellPath(), target.getFullyQualifiedName());
    }
  }
}
