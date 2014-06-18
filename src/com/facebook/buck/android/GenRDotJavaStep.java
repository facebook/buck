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

package com.facebook.buck.android;

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.AndroidPlatformTarget;
import com.facebook.buck.util.Verbosity;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

public class GenRDotJavaStep extends ShellStep {

  private final ImmutableList<Path> resDirectories;
  private final File androidManifest;
  private final Path genDirectoryPath;
  private final boolean isTempRDotJava;
  private final ImmutableSet<String> extraLibraryPackages;

  /**
   * Creates a command that will run {@code aapt} for the purpose of generating {@code R.java}.
   * Additionally, this command will generate the corresponding {@code R.txt} file.
   * @param resDirectories Directories of resource files. Will be specified with {@code -S} to
   *     {@code aapt}
   * @param genDirectoryPath Directory where {@code R.java} and potentially {@code R.txt} will be
   *     generated
   * @param libraryPackage Normally, {@code aapt} expects an {@code AndroidManifest.xml} so that it
 *     can extract the {@code package} attribute to determine the Java package of the generated
 *     {@code R.java} file. For this class, the client must specify the {@code package} directly
 *     rather than the path to {@code AndroidManifest.xml}. This precludes the need to keep a
 *     number of dummy {@code AndroidManifest.xml} files in the codebase.
   * @param isTempRDotJava If true, the values of the resource values in the generated
*     {@code R.java} will be meaningless.
*     <p>
*     If false, this command will produce an {@code R.java} file with resource values designed to
*     match those in an .apk that includes the resources.
   * @param extraLibraryPackages
   */
  public GenRDotJavaStep(
      ImmutableList<Path> resDirectories,
      Path genDirectoryPath,
      String libraryPackage,
      boolean isTempRDotJava,
      Set<String> extraLibraryPackages) {
    this.resDirectories = Preconditions.checkNotNull(resDirectories);

    File tmpDir = Files.createTempDir();
    tmpDir.deleteOnExit();

    // TODO(mbolin): This command is run fairly frequently, often for the same value of
    // libraryPackage, so consider generating these under buck-out/android, parameterized by
    // libraryPackage, so that AndroidManifest.xml is only written once per package. However, one
    // must be careful when doing this when --num-threads is greater than 1.
    // Another option is to require clients to provide an AndroidManifest.xml for each
    // android_resource() rule in the codebase. This may turn out to be helpful when running the
    // Android linter because then the user will specify the min/max values of Android for a
    // library.
    this.androidManifest = new File(tmpDir, "AndroidManifest.xml");
    try {
      String xml = String.format(
          "<manifest xmlns:android='http://schemas.android.com/apk/res/android' package='%s' />",
          libraryPackage);
      Files.write(
          xml,
          androidManifest,
          Charsets.UTF_8);
    } catch (IOException e) {
      Throwables.propagate(e);
    }

    this.genDirectoryPath = Preconditions.checkNotNull(genDirectoryPath);
    this.isTempRDotJava = isTempRDotJava;
    this.extraLibraryPackages = ImmutableSet.copyOf(extraLibraryPackages);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();

    builder.add(androidPlatformTarget.getAaptExecutable().toString());
    builder.add("package");

    // verbose flag, if appropriate.
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      builder.add("-v");
    }

    // Add all of the res/ directories.
    for (Path res : resDirectories) {
      builder.add("-S").add(res.toString());
    }

    builder.add("--output-text-symbols").add(genDirectoryPath.toString());
    if (isTempRDotJava) {
      builder.add("--non-constant-id");
    }

    if (!extraLibraryPackages.isEmpty()) {
      builder.add("--extra-packages").add(Joiner.on(':').join(extraLibraryPackages));
    }

    // Add the remaining flags.
    builder.add("-M").add(androidManifest.getAbsolutePath());
    builder.add("-m").add("-J").add(genDirectoryPath.toString());
    builder.add("--auto-add-overlay");
    builder.add("-I").add(androidPlatformTarget.getAndroidJar().toString());

    return builder.build();
  }

  @Override
  protected void onProcessFinished(int exitCode) {
    super.onProcessFinished(exitCode);

    if (androidManifest.exists() && androidManifest.isFile()) {
      androidManifest.delete();
    }
  }

  @Override
  public String getShortName() {
    return String.format("aapt_package");
  }

  @Override
  protected boolean shouldPrintStderr(Verbosity verbosity) {
    // Print out errors about missing resource dependecies.
    return verbosity.shouldPrintStandardInformation();
  }
}
