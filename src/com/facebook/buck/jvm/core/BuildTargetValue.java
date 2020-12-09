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
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.path.ForwardRelativePath;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import com.facebook.buck.io.filesystem.BaseBuckPaths;
import org.immutables.value.Value;

/**
 * {@link BuildTarget} representation used in java compilation. This class include only fields that
 * used in java compilation.
 */
@BuckStyleValue
public abstract class BuildTargetValue {

  /** java compilation build target type */
  public enum Type {
    LIBRARY,
    SOURCE_ABI,
    SOURCE_ONLY_ABI
  }

  public abstract Type getType();

  // TODO msemko needed only for Kotlin
  public abstract ForwardRelativePath getBasePathForBaseName();

  // TODO msemko needed only for Kotlin
  public abstract String getShortNameAndFlavorPostfix();

  // TODO msemko needed only for Kotlin
  public abstract String getShortName();

  public abstract String getFullyQualifiedName();

  // TODO msemko needed only for Kotlin
  public abstract ForwardRelativePath getCellRelativeBasePath();

  // TODO msemko needed only for Kotlin
  public abstract boolean isFlavored();

  @Value.Derived
  public boolean hasAbiJar() {
    return isSourceAbi() || isSourceOnlyAbi();
  }

  @Value.Derived
  public boolean isLibraryJar() {
    return getType() == Type.LIBRARY;
  }

  @Value.Derived
  public boolean isSourceAbi() {
    return getType() == Type.SOURCE_ABI;
  }

  @Value.Derived
  public boolean isSourceOnlyAbi() {
    return getType() == Type.SOURCE_ONLY_ABI;
  }

  @Override
  public String toString() {
    return getFullyQualifiedName();
  }

  /** Creates {@link BuildTargetValue} */
  public static BuildTargetValue of(BuildTarget buildTarget, BaseBuckPaths baseBuckPaths) {
    return of(
        getType(buildTarget),
        BuildTargetPaths.getBasePathForBaseName(
            baseBuckPaths.shouldIncludeTargetConfigHash(), buildTarget),
        buildTarget.getShortNameAndFlavorPostfix(),
        buildTarget.getShortName(),
        buildTarget.getFullyQualifiedName(),
        buildTarget.getCellRelativeBasePath().getPath(),
        buildTarget.isFlavored());
  }

  /** Creates {@link BuildTargetValue} */
  public static BuildTargetValue of(
      Type type,
      ForwardRelativePath basePathForBaseName,
      String shortNameAndFlavorPostfix,
      String shortName,
      String fullyQualifiedName,
      ForwardRelativePath cellRelativeBasePath,
      boolean isFlavored) {
    return ImmutableBuildTargetValue.ofImpl(
        type,
        basePathForBaseName,
        shortNameAndFlavorPostfix,
        shortName,
        fullyQualifiedName,
        cellRelativeBasePath,
        isFlavored);
  }

  private static Type getType(BuildTarget buildTarget) {
    if (JavaAbis.isLibraryTarget(buildTarget)) {
      return Type.LIBRARY;
    }

    if (JavaAbis.isSourceAbiTarget(buildTarget)) {
      return Type.SOURCE_ABI;
    }

    if (JavaAbis.isSourceOnlyAbiTarget(buildTarget)) {
      return Type.SOURCE_ONLY_ABI;
    }

    throw new IllegalStateException(buildTarget + " doesn't supported");
  }
}
