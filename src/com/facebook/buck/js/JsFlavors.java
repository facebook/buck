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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.ImmutableFlavor;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.hash.Hashing;

import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class JsFlavors {
  public static final ImmutableFlavor ANDROID = ImmutableFlavor.of("android");
  public static final ImmutableFlavor IOS = ImmutableFlavor.of("ios");
  public static final ImmutableFlavor PROD = ImmutableFlavor.of("prod");

  private static final ImmutableSet<Flavor> platforms = ImmutableSet.of(ANDROID, IOS);
  private static final ImmutableSet<Flavor> other = ImmutableSet.of(PROD);
  private static final String fileFlavorPrefix = "file-";

  public static boolean validateFlavors(ImmutableSet<Flavor> flavors) {
    return Sets.intersection(flavors, platforms).size() < 2 &&
           other.containsAll(Sets.difference(flavors, platforms));
  }

  public static String getPlatform(ImmutableSet<Flavor> flavors) {
    return flavors.contains(IOS) ? "ios" : "android";
  }

  public static Flavor fileFlavorForSourcePath(final Path path) {
    final String hash = Hashing.sha1()
        .hashString(MorePaths.pathWithUnixSeparators(path), Charsets.UTF_8)
        .toString()
        .substring(0, 10);
    final String safeFileName = Flavor.replaceInvalidCharacters(path.getFileName().toString());
    return ImmutableFlavor.of(fileFlavorPrefix + safeFileName + "-" + hash);
  }

  public static Optional<SourcePath> extractSourcePath(
      ImmutableBiMap<Flavor, SourcePath> flavorsToSources,
      Stream<Flavor> flavors) {
    return flavors
        .filter(JsFlavors::isFileFlavor)
        .findFirst()
        .map(flavorsToSources::get);
  }

  public static boolean isFileFlavor(Flavor flavor) {
    return flavor.toString().startsWith(fileFlavorPrefix);
  }

  private JsFlavors() {}
}
