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

package com.facebook.buck.jvm.core;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.FlavorSet;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import com.google.common.collect.ImmutableSet;
import org.immutables.value.Value;

/**
 * {@link BuildTarget} representation used in java compilation. This class doesn't include {@link
 * com.facebook.buck.core.model.TargetConfiguration} and any other {@link BuildTarget} specific
 * internal data (such as {@code hash}) that we don't used in java compilation.
 */
@BuckStyleValue
public abstract class BuildTargetValue {

  public abstract ForwardRelativePath getBasePathForBaseName();

  @Value.Derived
  public boolean isFlavored() {
    return !getFlavors().isEmpty();
  }

  public abstract String getShortNameAndFlavorPostfix();

  @Value.Derived
  public boolean hasAbiJar() {
    return isSourceAbi() || isSourceOnlyAbi();
  }

  public abstract boolean isLibraryJar();

  public abstract String getShortName();

  public abstract String getFullyQualifiedName();

  public abstract boolean isSourceAbi();

  public abstract boolean isSourceOnlyAbi();

  public abstract ForwardRelativePath getCellRelativeBasePath();

  public abstract FlavorSet getFlavors();

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  /** Creates {@link BuildTargetValue} */
  public static BuildTargetValue of(BuildTarget buildTarget, BaseBuckPaths baseBuckPaths) {
    ForwardRelativePath basePathForBaseName =
        BuildTargetPaths.getBasePathForBaseName(
            baseBuckPaths.shouldIncludeTargetConfigHash(), buildTarget);

    boolean libraryTarget = JavaAbis.isLibraryTarget(buildTarget);
    boolean sourceAbiTarget = JavaAbis.isSourceAbiTarget(buildTarget);
    boolean sourceOnlyAbiTarget = JavaAbis.isSourceOnlyAbiTarget(buildTarget);

    ForwardRelativePath cellRelativeBasePath = buildTarget.getCellRelativeBasePath().getPath();

    return of(
        basePathForBaseName,
        buildTarget.getShortNameAndFlavorPostfix(),
        libraryTarget,
        buildTarget.getShortName(),
        buildTarget.getFullyQualifiedName(),
        sourceAbiTarget,
        sourceOnlyAbiTarget,
        cellRelativeBasePath,
        buildTarget.getFlavors());
  }

  /** Creates {@link BuildTargetValue} */
  public static BuildTargetValue of(
      ForwardRelativePath basePathForBaseName,
      String shortNameAndFlavorPostfix,
      boolean libraryJar,
      String shortName,
      String fullyQualifiedName,
      boolean sourceAbi,
      boolean sourceOnlyAbi,
      ForwardRelativePath cellRelativeBasePath,
      FlavorSet flavors) {
    return ImmutableBuildTargetValue.ofImpl(
        basePathForBaseName,
        shortNameAndFlavorPostfix,
        libraryJar,
        shortName,
        fullyQualifiedName,
        sourceAbi,
        sourceOnlyAbi,
        cellRelativeBasePath,
        flavors);
  }

  public BuildTargetValue() {}

  /** Creates {@link BuildTargetValue} with Source ABI from the given {@code buildTargetValue} */
  public static BuildTargetValue sourceAbiTarget(BuildTargetValue buildTargetValue) {
    FlavorSet flavors =
        buildTargetValue.getFlavors().withAdded(ImmutableSet.of(JavaAbis.SOURCE_ABI_FLAVOR));
    String flavorPostfix = getFlavorPostfix(flavors);

    String shortName = buildTargetValue.getShortName();
    ForwardRelativePath cellRelativeBasePath = buildTargetValue.getCellRelativeBasePath();
    return ImmutableBuildTargetValue.ofImpl(
        buildTargetValue.getBasePathForBaseName(),
        shortName + flavorPostfix,
        false,
        shortName,
        getFullyQualifiedName(shortName, cellRelativeBasePath) + flavorPostfix,
        true,
        false,
        cellRelativeBasePath,
        flavors);
  }

  /**
   * Creates {@link BuildTargetValue} with Source Only ABI from the given {@code buildTargetValue}
   */
  public static BuildTargetValue sourceOnlyAbiTarget(BuildTargetValue buildTargetValue) {
    FlavorSet flavors =
        buildTargetValue.getFlavors().withAdded(ImmutableSet.of(JavaAbis.SOURCE_ONLY_ABI_FLAVOR));
    String flavorPostfix = getFlavorPostfix(flavors);

    String shortName = buildTargetValue.getShortName();
    ForwardRelativePath cellRelativeBasePath = buildTargetValue.getCellRelativeBasePath();
    return ImmutableBuildTargetValue.ofImpl(
        buildTargetValue.getBasePathForBaseName(),
        shortName + flavorPostfix,
        false,
        buildTargetValue.getShortName(),
        getFullyQualifiedName(shortName, cellRelativeBasePath) + flavorPostfix,
        false,
        true,
        cellRelativeBasePath,
        flavors);
  }

  /** Creates a library {@link BuildTargetValue} from the given {@code buildTargetValue} */
  public static BuildTargetValue libraryTarget(BuildTargetValue buildTargetValue) {
    FlavorSet flavors =
        buildTargetValue
            .getFlavors()
            .without(
                ImmutableSet.of(
                    JavaAbis.CLASS_ABI_FLAVOR,
                    JavaAbis.SOURCE_ABI_FLAVOR,
                    JavaAbis.SOURCE_ONLY_ABI_FLAVOR,
                    JavaAbis.VERIFIED_SOURCE_ABI_FLAVOR));
    String flavorPostfix = getFlavorPostfix(flavors);

    String shortName = buildTargetValue.getShortName();
    ForwardRelativePath cellRelativeBasePath = buildTargetValue.getCellRelativeBasePath();

    return ImmutableBuildTargetValue.ofImpl(
        buildTargetValue.getBasePathForBaseName(),
        shortName + flavorPostfix,
        true,
        buildTargetValue.getShortName(),
        getFullyQualifiedName(shortName, cellRelativeBasePath) + flavorPostfix,
        false,
        false,
        cellRelativeBasePath,
        flavors);
  }

  private static String getFlavorPostfix(FlavorSet flavors) {
    if (flavors.isEmpty()) {
      return "";
    }
    return "#" + flavors.toCommaSeparatedString();
  }

  private static String getFullyQualifiedName(
      String shortName, ForwardRelativePath cellRelativeBasePath) {
    return "//" + cellRelativeBasePath + ":" + shortName;
  }
}
