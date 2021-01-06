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

package com.facebook.buck.core.parser.buildtargetparser;

import com.facebook.buck.core.cell.name.CanonicalCellName;
import com.facebook.buck.core.cell.nameresolver.CellNameResolver;
import com.facebook.buck.core.exceptions.BuckUncheckedExecutionException;
import com.facebook.buck.core.exceptions.BuildTargetParseException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.BaseName;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UnconfiguredBuildTarget;
import com.facebook.buck.core.model.UnflavoredBuildTarget;
import com.facebook.buck.util.stream.RichStream;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSortedSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import javax.annotation.Nullable;

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
  UnconfiguredBuildTarget parse(
      String buildTargetName,
      @Nullable BaseName buildTargetBaseName,
      boolean allowWildCards,
      CellNameResolver cellNameResolver) {
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
      BaseName baseName;
      if (parts.get(0).isEmpty()) {
        if (buildTargetBaseName == null) {
          throw new HumanReadableException("relative path is not allowed");
        }
        baseName = buildTargetBaseName;
      } else {
        try {
          baseName = BaseName.of(parts.get(0));
        } catch (Exception e) {
          throw new HumanReadableException("incorrect base name: " + parts.get(0));
        }
      }
      String shortName = parts.get(1);
      Iterable<String> flavorNames = new HashSet<>();
      int hashIndex = shortName.indexOf('#');
      if (hashIndex != -1 && hashIndex < shortName.length()) {
        flavorNames = flavorParser.parseFlavorString(shortName.substring(hashIndex + 1));
        shortName = shortName.substring(0, hashIndex);
      }

      // Set the cell path correctly. Because the cellNames comes from the owning cell we can
      // be sure that if this doesn't throw an exception the target cell is visible to the
      // owning cell.
      UnflavoredBuildTarget unflavoredBuildTarget =
          UnflavoredBuildTarget.of(canonicalCellName, baseName, shortName);
      ImmutableSortedSet<Flavor> flavors =
          RichStream.from(flavorNames)
              .map(InternalFlavor::of)
              .collect(ImmutableSortedSet.toImmutableSortedSet(FlavorSet.FLAVOR_ORDERING));
      return UnconfiguredBuildTarget.of(unflavoredBuildTarget, FlavorSet.copyOf(flavors));
    } catch (HumanReadableException e) {
      throw new BuildTargetParseException(
          e,
          String.format("When parsing %s: %s.", buildTargetName, e.getHumanReadableErrorMessage()));
    } catch (Exception e) {
      throw new BuckUncheckedExecutionException(e, "When parsing %s.", buildTargetName);
    }
  }
}
