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

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.TargetConfiguration;
import com.facebook.buck.core.model.impl.BuildTargetPaths;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.cxx.toolchain.CxxPlatforms;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.util.exceptions.BuckUncheckedExecutionException;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;

/** Utility functions */
public class OcamlUtil {
  private static final Logger LOG = Logger.get(OcamlUtil.class);

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

  /** Creates a file path for a linker arg file */
  public static Path makeLinkerArgFilePath(ProjectFilesystem filesystem, BuildTarget buildTarget) {
    return makeArgFilePath(filesystem, buildTarget, "cclib");
  }

  /** Creates a file path for an arg file */
  public static Path makeArgFilePath(
      ProjectFilesystem filesystem, BuildTarget buildTarget, String prefix) {
    String scratchFileName = String.format("%s.argfile", prefix);

    // NOTE: the single %s format placeholder is required by the getScratchPath method
    String scratchFileNameFormat = "%s" + scratchFileName;
    return BuildTargetPaths.getScratchPath(filesystem, buildTarget, scratchFileNameFormat);
  }

  /** Creates an arg file with a given list of flags */
  public static ImmutableList<String> makeArgFile(
      ProjectFilesystem filesystem,
      Path argFile,
      ImmutableList<String> toolPrefix,
      String flag,
      Iterable<String> flagList) {
    ImmutableList<String> immutableFlags;

    try {
      String content = Joiner.on(' ').join(flagList);
      LOG.verbose("The flags file content is %s", content);
      LOG.info("The temporary flags file is %s", argFile.toString());

      Path parent = argFile.getParent();
      LOG.info("The parent path is %s", parent);

      filesystem.mkdirs(parent);

      Path fileName = argFile.getFileName();
      LOG.info("The file name is %s", fileName.toString());

      argFile = filesystem.createTempFile(parent, fileName.toString(), ".flags");

      filesystem.writeContentsToPath(content, argFile);
      LOG.info("Wrote the flags to path %s", argFile.toString());

      immutableFlags = ImmutableList.of("-" + flag, "@" + argFile.toString());
    } catch (IOException ioe) {
      LOG.error("Couldn't create an arg file.");
      throw new BuckUncheckedExecutionException(ioe, "When creating arg file at %s", argFile);
    }

    LOG.info(
        "The '%s' flags: '%s'",
        Joiner.on(' ').join(toolPrefix), Joiner.on(' ').join(immutableFlags));

    return immutableFlags;
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

  static Iterable<BuildTarget> getParseTimeDeps(
      TargetConfiguration targetConfiguration, OcamlPlatform platform) {
    ImmutableSet.Builder<BuildTarget> deps = ImmutableSet.builder();
    deps.addAll(platform.getCCompiler().getParseTimeDeps(targetConfiguration));
    deps.addAll(platform.getCxxCompiler().getParseTimeDeps(targetConfiguration));
    deps.addAll(platform.getCPreprocessor().getParseTimeDeps(targetConfiguration));
    deps.addAll(CxxPlatforms.getParseTimeDeps(targetConfiguration, platform.getCxxPlatform()));
    return deps.build();
  }
}
