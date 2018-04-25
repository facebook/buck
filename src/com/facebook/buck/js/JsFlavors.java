/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.model.Flavor;
import com.facebook.buck.core.model.FlavorDomain;
import com.facebook.buck.core.model.InternalFlavor;
import com.facebook.buck.core.model.UserFlavor;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.io.file.MorePaths;
import com.facebook.buck.util.types.Either;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class JsFlavors {
  public static final UserFlavor ANDROID = UserFlavor.of("android", "Build JS for Android");
  public static final UserFlavor IOS = UserFlavor.of("ios", "Build JS for iOS");
  public static final UserFlavor RELEASE = UserFlavor.of("release", "Optimize for release builds");
  public static final UserFlavor RAM_BUNDLE_FILES =
      UserFlavor.of("rambundle-files", "Output code as file-based RAM bundle. For Android.");
  public static final UserFlavor RAM_BUNDLE_INDEXED =
      UserFlavor.of(
          "rambundle-indexed",
          "Output code as indexed RAM bundle. For iOS. Only use for Android if copied to disk on "
              + "first run");
  public static final UserFlavor SOURCE_MAP = UserFlavor.of("source_map", "Expose source map");
  public static final UserFlavor MISC = UserFlavor.of("misc", "Expose misc directory");
  public static final UserFlavor DEPENDENCY_FILE =
      UserFlavor.of("dependencies", "Build dependency file");

  public static final FlavorDomain<String> OPTIMIZATION_DOMAIN =
      new FlavorDomain<>("Build optimization", ImmutableMap.of(RELEASE, "--release"));
  public static final FlavorDomain<String> PLATFORM_DOMAIN =
      new FlavorDomain<>(
          "Mobile platforms",
          ImmutableMap.of(
              ANDROID, "--platform android",
              IOS, "--platform ios"));
  public static final FlavorDomain<String> RAM_BUNDLE_DOMAIN =
      new FlavorDomain<>(
          "RAM bundle types",
          ImmutableMap.of(
              RAM_BUNDLE_FILES, "--files-rambundle",
              RAM_BUNDLE_INDEXED, "--indexed-rambundle"));
  public static final FlavorDomain<Object> OUTPUT_OPTIONS_DOMAIN =
      new FlavorDomain<>(
          "Output options",
          ImmutableMap.of(SOURCE_MAP, SOURCE_MAP, DEPENDENCY_FILE, DEPENDENCY_FILE, MISC, MISC));

  public static final InternalFlavor ANDROID_RESOURCES = InternalFlavor.of("_res_");
  public static final InternalFlavor FORCE_JS_BUNDLE = InternalFlavor.of("_js_");
  public static final InternalFlavor LIBRARY_FILES = InternalFlavor.of("_files_");

  private static final String fileFlavorPrefix = "file-";

  public static boolean validateFlavors(
      ImmutableSet<Flavor> flavors, Iterable<FlavorDomain<?>> allowableDomains) {

    ImmutableSet.Builder<Flavor> allowableFlavors = ImmutableSet.builder();
    for (FlavorDomain<?> domain : allowableDomains) {
      // verify only one flavor of each domain is present
      domain.getFlavor(flavors);
      allowableFlavors.addAll(domain.getFlavors());
    }

    return allowableFlavors.build().containsAll(flavors);
  }

  public static Flavor fileFlavorForSourcePath(Path path) {
    String hash =
        Hashing.sha1()
            .hashString(MorePaths.pathWithUnixSeparators(path), Charsets.UTF_8)
            .toString()
            .substring(0, 10);
    String safeFileName = Flavor.replaceInvalidCharacters(path.getFileName().toString());
    return InternalFlavor.of(fileFlavorPrefix + safeFileName + "-" + hash);
  }

  public static Optional<Either<SourcePath, Pair<SourcePath, String>>> extractSourcePath(
      ImmutableBiMap<Flavor, Either<SourcePath, Pair<SourcePath, String>>> flavorsToSources,
      Stream<Flavor> flavors) {
    return flavors.filter(JsFlavors::isFileFlavor).findFirst().map(flavorsToSources::get);
  }

  public static boolean isFileFlavor(Flavor flavor) {
    return flavor.toString().startsWith(fileFlavorPrefix);
  }

  private JsFlavors() {}

  public static String bundleJobArgs(Set<Flavor> flavors) {
    return Stream.of(
            PLATFORM_DOMAIN.getValue(flavors),
            RAM_BUNDLE_DOMAIN.getValue(flavors),
            OPTIMIZATION_DOMAIN.getValue(flavors))
        .filter(Optional::isPresent)
        .map(Optional::get)
        .collect(Collectors.joining(" "));
  }

  public static String platformArgForRelease(Set<Flavor> flavors) {
    return PLATFORM_DOMAIN
        .getValue(flavors)
        .orElseThrow(
            () ->
                new HumanReadableException("A platform flavor must be passed for release builds"));
  }
}
