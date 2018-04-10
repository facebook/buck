/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.features.ocaml;

import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

/** Utility functions */
public class OcamlUtil {
  private OcamlUtil() {}

  /**
   * Constructs a Predicate instance which returns true if the input argument ends with any String
   * in extensions
   *
   * @param extensions for which to return true
   * @return a Predicate instance
   */
  public static Predicate<? super Path> ext(String... extensions) {
    return (Predicate<Path>)
        input -> {
          String strInput = input.toString();
          for (String ext : extensions) {
            if (strInput.endsWith(ext)) {
              return true;
            }
          }
          return false;
        };
  }

  public static Predicate<? super SourcePath> sourcePathExt(
      SourcePathResolver resolver, String... extensions) {
    return (Predicate<SourcePath>)
        input -> {
          String strInput = resolver.getRelativePath(input).toString();
          for (String ext : extensions) {
            if (strInput.endsWith(ext)) {
              return true;
            }
          }
          return false;
        };
  }

  static ImmutableSet<Path> getExtensionVariants(Path output, String... extensions) {
    String withoutExtension = stripExtension(output.toString());
    ImmutableSet.Builder<Path> builder = ImmutableSet.builder();
    for (String ext : extensions) {
      builder.add(Paths.get(withoutExtension + ext));
    }
    return builder.build();
  }

  static String stripExtension(String fileName) {
    int index = fileName.lastIndexOf('.');

    // if dot is in the first position,
    // we are dealing with a hidden file rather than an extension
    return (index > 0) ? fileName.substring(0, index) : fileName;
  }

  static Iterable<BuildTarget> getParseTimeDeps(OcamlPlatform platform) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    deps.addAll(platform.getCCompiler().getParseTimeDeps());
    deps.addAll(platform.getCxxCompiler().getParseTimeDeps());
    deps.addAll(platform.getCPreprocessor().getParseTimeDeps());
    deps.addAll(CxxPlatforms.getParseTimeDeps(platform.getCxxPlatform()));
    return deps.build();
  }
}
