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

import com.facebook.buck.core.cell.CellNameResolver;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.cell.NewCellPathResolver;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.CanonicalCellName;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTargetView;
import com.facebook.buck.core.model.UnflavoredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnconfiguredBuildTargetView;
import com.facebook.buck.core.model.impl.ImmutableUnflavoredBuildTargetView;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Splitter;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Use {@link com.facebook.buck.core.parser.buildtargetpattern.UnconfiguredBuildTargetParser
 * instead} which is stateless and environment-agnostic
 */
@Deprecated
class BuildTargetParser {

  /** The BuildTargetParser is stateless, so this single instance can be shared. */
  public static final BuildTargetParser INSTANCE = new BuildTargetParser();

  static final String BUILD_RULE_PREFIX = "//";
  private static final String BUILD_RULE_SEPARATOR = ":";
  private static final Splitter BUILD_RULE_SEPARATOR_SPLITTER = Splitter.on(BUILD_RULE_SEPARATOR);

  private final Interner<UnconfiguredBuildTargetView> flavoredTargetCache =
      Interners.newWeakInterner();

  private final FlavorParser flavorParser = new FlavorParser();

  private BuildTargetParser() {
    // this is stateless. There's no need to do anything other than grab the instance needed.
  }

  /**
   * Creates {@link BuildTarget} using a target name.
   *
   * <p>The target name can either be fully-qualified or relative. Relative target names are
   * resolved using the provided base name (which can also be empty when relative target name is at
   * the root of a cell). For example, a target name with base name "java/com/company" and relative
   * name "app" is equal to the fully-qualified target name "java/com/company:app".
   *
   * @param buildTargetName either a fully-qualified or relative target name.
   * @param buildTargetBaseName the base name of the target.
   * @param allowWildCards whether to allow a colon at the end of the target name. This is used when
   *     parsing target name patterns.
   */
  UnconfiguredBuildTargetView parse(
      CellPathResolver legacyCellPathResolver,
      String buildTargetName,
      String buildTargetBaseName,
      boolean allowWildCards) {
    CellNameResolver cellNameResolver = legacyCellPathResolver.getCellNameResolver();
    NewCellPathResolver cellPathResolver = legacyCellPathResolver.getNewCellPathResolver();

    if (buildTargetName.endsWith(BUILD_RULE_SEPARATOR) && !allowWildCards) {
      throw new BuildTargetParseException(
          String.format("%s cannot end with a colon", buildTargetName));
    }

    Optional<String> givenCellName = Optional.empty();
    String targetAfterCell = buildTargetName;
    if (buildTargetName.contains(BUILD_RULE_PREFIX)
        && !buildTargetName.startsWith(BUILD_RULE_PREFIX)) {
      int slashIndex = buildTargetName.indexOf(BUILD_RULE_PREFIX);
      // in order to support Skylark way of referencing repositories but also preserve backwards
      // compatibility, the '@' is simply ignored if it's present
      int repoNameStartIndex = (buildTargetName.charAt(0) == '@') ? 1 : 0;
      givenCellName = Optional.of(buildTargetName.substring(repoNameStartIndex, slashIndex));
      targetAfterCell = buildTargetName.substring(slashIndex);
    }

    if (givenCellName.isPresent() && givenCellName.get().isEmpty()) {
      throw new BuildTargetParseException("Cell name must not be empty.");
    }

    List<String> parts = BUILD_RULE_SEPARATOR_SPLITTER.splitToList(targetAfterCell);
    if (parts.size() != 2) {
      throw new BuildTargetParseException(
          String.format(
              "%s must contain exactly one colon (found %d)", buildTargetName, parts.size() - 1));
    }

    try {

      CanonicalCellName canonicalCellName = cellNameResolver.getName(givenCellName);
      String baseName = parts.get(0).isEmpty() ? buildTargetBaseName : parts.get(0);
      String shortName = parts.get(1);
      Iterable<String> flavorNames = new HashSet<>();
      int hashIndex = shortName.indexOf('#');
      if (hashIndex != -1 && hashIndex < shortName.length()) {
        flavorNames = flavorParser.parseFlavorString(shortName.substring(hashIndex + 1));
        shortName = shortName.substring(0, hashIndex);
      }

      Objects.requireNonNull(baseName);
      // On Windows, baseName may contain backslashes, which are not permitted by BuildTarget.
      baseName = baseName.replace('\\', '/');
      BaseNameParser.checkBaseName(baseName, buildTargetName);

      Path cellPath = cellPathResolver.getCellPath(canonicalCellName);

      // Set the cell path correctly. Because the cellNames comes from the owning cell we can
      // be sure that if this doesn't throw an exception the target cell is visible to the
      // owning cell.
      UnflavoredBuildTargetView unflavoredBuildTargetView =
          ImmutableUnflavoredBuildTargetView.of(cellPath, canonicalCellName, baseName, shortName);
      return flavoredTargetCache.intern(
          ImmutableUnconfiguredBuildTargetView.of(
              unflavoredBuildTargetView, RichStream.from(flavorNames).map(InternalFlavor::of)));
    } catch (HumanReadableException e) {
      throw new BuildTargetParseException(
          e,
          String.format("When parsing %s: %s.", buildTargetName, e.getHumanReadableErrorMessage()));
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(e, "When parsing %s.", buildTargetName);
    }
  }
}
