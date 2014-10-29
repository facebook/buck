/*
 * Copyright 2014-present Facebook, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may
 *  not use this file except in compliance with the License. You may obtain
 *  a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.facebook.buck.android;

import com.android.common.SdkConstants;
import com.facebook.buck.android.AndroidBinary.TargetCpuType;
import com.facebook.buck.step.AbstractExecutionStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.CopyStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

public class CopyNativeLibraries {

  // Utility class.
  private CopyNativeLibraries() {}

  public static void copyNativeLibrary(Path sourceDir,
      final Path destinationDir,
      ImmutableSet<TargetCpuType> cpuFilters,
      ImmutableList.Builder<Step> steps) {

    if (cpuFilters.isEmpty()) {
      steps.add(
          CopyStep.forDirectory(
              sourceDir,
              destinationDir,
              CopyStep.DirectoryMode.CONTENTS_ONLY));
    } else {
      for (TargetCpuType cpuType : cpuFilters) {
        Optional<String> abiDirectoryComponent = getAbiDirectoryComponent(cpuType);
        Preconditions.checkState(abiDirectoryComponent.isPresent());

        final Path libSourceDir = sourceDir.resolve(abiDirectoryComponent.get());
        Path libDestinationDir = destinationDir.resolve(abiDirectoryComponent.get());

        final MkdirStep mkDirStep = new MkdirStep(libDestinationDir);
        final CopyStep copyStep = CopyStep.forDirectory(
            libSourceDir,
            libDestinationDir,
            CopyStep.DirectoryMode.CONTENTS_ONLY);
        steps.add(
            new Step() {
              @Override
              public int execute(ExecutionContext context) {
                if (!context.getProjectFilesystem().exists(libSourceDir)) {
                  return 0;
                }
                if (mkDirStep.execute(context) == 0 && copyStep.execute(context) == 0) {
                  return 0;
                }
                return 1;
              }

              @Override
              public String getShortName() {
                return "copy_native_libraries";
              }

              @Override
              public String getDescription(ExecutionContext context) {
                ImmutableList.Builder<String> stringBuilder = ImmutableList.builder();
                stringBuilder.add(String.format("[ -d %s ]", libSourceDir.toString()));
                stringBuilder.add(mkDirStep.getDescription(context));
                stringBuilder.add(copyStep.getDescription(context));
                return Joiner.on(" && ").join(stringBuilder.build());
              }
            });
      }
    }

    // Rename native files named like "*-disguised-exe" to "lib*.so" so they will be unpacked
    // by the Android package installer.  Then they can be executed like normal binaries
    // on the device.
    steps.add(
        new AbstractExecutionStep("rename_native_executables") {
          @Override
          public int execute(ExecutionContext context) {

            ProjectFilesystem filesystem = context.getProjectFilesystem();
            final ImmutableSet.Builder<Path> executablesBuilder = ImmutableSet.builder();
            try {
              filesystem.walkRelativeFileTree(destinationDir, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                      if (file.toString().endsWith("-disguised-exe")) {
                        executablesBuilder.add(file);
                      }
                      return FileVisitResult.CONTINUE;
                    }
                  });
              for (Path exePath : executablesBuilder.build()) {
                Path fakeSoPath = Paths.get(
                    exePath.toString().replaceAll("/([^/]+)-disguised-exe$", "/lib$1.so"));
                filesystem.move(exePath, fakeSoPath);
              }
            } catch (IOException e) {
              context.logError(e, "Renaming native executables failed.");
              return 1;
            }
            return 0;
          }
        });
  }

  /**
   * Native libraries compiled for different CPU architectures are placed in the
   * respective ABI subdirectories, such as 'armeabi', 'armeabi-v7a', 'x86' and 'mips'.
   * This looks at the cpu filter and returns the correct subdirectory. If cpu filter is
   * not present or not supported, returns Optional.absent();
   */
  private static Optional<String> getAbiDirectoryComponent(TargetCpuType cpuType) {
    String component = null;
    if (cpuType.equals(TargetCpuType.ARM)) {
      component = SdkConstants.ABI_ARMEABI;
    } else if (cpuType.equals(TargetCpuType.ARMV7)) {
      component = SdkConstants.ABI_ARMEABI_V7A;
    } else if (cpuType.equals(TargetCpuType.X86)) {
      component = SdkConstants.ABI_INTEL_ATOM;
    } else if (cpuType.equals(TargetCpuType.MIPS)) {
      component = SdkConstants.ABI_MIPS;
    }
    return Optional.fromNullable(component);
  }
}
