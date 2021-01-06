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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetLocator;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jetbrains.annotations.Nullable;

/** Helper class to handle deserialization of buck query. */
public class TargetMetadata {
  public BuckTarget target;
  public @Nullable List<BuckTarget> deps;
  public @Nullable List<BuckTargetPattern> visibility; // null means PUBLIC
  public @Nullable List<String> srcs;
  public @Nullable List<String> resources;

  static TargetMetadata from(
      BuckTargetLocator buckTargetLocator, BuckTarget target, JsonObject payload) {
    TargetMetadata targetMetadata = new TargetMetadata();
    targetMetadata.target = target;
    targetMetadata.srcs = stringListOrNull(payload.get("srcs"));
    targetMetadata.resources = stringListOrNull(payload.get("resources"));

    // Deps are a list of BuckTargets
    targetMetadata.deps =
        Optional.ofNullable(stringListOrNull(payload.get("deps")))
            .map(
                deps ->
                    deps.stream()
                        .map(s -> BuckTarget.parse(s).map(buckTargetLocator::resolve).orElse(null))
                        .collect(Collectors.toList()))
            .orElse(null);

    // Visibilility falls in one of three cases:
    //   (1) if unspecified => means visibility is limited to the current package
    //   (2) contains "PUBLIC" => available everywhere
    //   (3) otherwise is a list of buck target patterns where it is visible
    List<String> optionalVisibility = stringListOrNull(payload.get("visibility"));
    if (optionalVisibility == null) {
      targetMetadata.visibility =
          Collections.singletonList(target.asPattern().asPackageMatchingPattern());
    } else if (optionalVisibility.contains("PUBLIC")) {
      targetMetadata.visibility = null; //
    } else {
      targetMetadata.visibility =
          optionalVisibility.stream()
              .map(p -> BuckTargetPattern.parse(p).map(buckTargetLocator::resolve).orElse(null))
              .collect(Collectors.toList());
    }

    return targetMetadata;
  }

  static @Nullable List<String> stringListOrNull(@Nullable JsonElement jsonElement) {
    if (jsonElement == null) {
      return null;
    }
    return new Gson().fromJson(jsonElement, new TypeToken<List<String>>() {}.getType());
  }

  boolean isVisibleTo(BuckTarget target) {
    if (visibility == null) {
      return true;
    }
    return visibility.stream().anyMatch(pattern -> pattern.matches(target));
  }

  boolean hasDependencyOn(BuckTarget target) {
    if (deps == null) {
      return false;
    } else {
      return deps.stream().anyMatch(dep -> dep.equals(target));
    }
  }

  boolean contains(BuckTarget targetFile) {
    if (!target.asPattern().asPackageMatchingPattern().matches(targetFile)) {
      return false;
    }
    String relativeToBuildFile = targetFile.getRuleName();
    return (srcs != null && srcs.contains(relativeToBuildFile))
        || (resources != null && resources.contains(relativeToBuildFile));
  }
}
