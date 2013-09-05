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
import com.facebook.buck.util.Functions;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class ProGuardObfuscateStep extends ShellStep {

  private final String generatedProGuardConfig;

  private final Set<String> customProguardConfigs;

  private final Map<String, String> inputAndOutputEntries;

  private final Set<String> additionalLibraryJarsForProguard;

  private final boolean useAndroidProguardConfigWithOptimizations;

  private final String proguardDirectory;

  /**
   * @param generatedProGuardConfig Proguard configuration as produced by aapt.
   * @param customProguardConfigs Main rule and its dependencies proguard configurations.
   * @param useProguardOptimizations Whether to include the Android SDK proguard defaults.
   * @param inputAndOutputEntries Map of input/output pairs to proguard.  The key represents an
   *     input jar (-injars); the value an output jar (-outjars).
   * @param additionalLibraryJarsForProguard Libraries that are not operated upon by proguard but
   *     needed to resolve symbols.
   * @param proguardDirectory Output directory for various proguard-generated meta artifacts.
   */
  public ProGuardObfuscateStep(
      String generatedProGuardConfig,
      Set<String> customProguardConfigs,
      boolean useProguardOptimizations,
      Map<String, String> inputAndOutputEntries,
      Set<String> additionalLibraryJarsForProguard,
      String proguardDirectory) {
    this.generatedProGuardConfig = Preconditions.checkNotNull(generatedProGuardConfig);
    this.customProguardConfigs = ImmutableSet.copyOf(customProguardConfigs);
    this.useAndroidProguardConfigWithOptimizations = useProguardOptimizations;
    this.inputAndOutputEntries = ImmutableMap.copyOf(inputAndOutputEntries);
    this.additionalLibraryJarsForProguard = ImmutableSet.copyOf(additionalLibraryJarsForProguard);
    this.proguardDirectory = Preconditions.checkNotNull(proguardDirectory);
  }

  @Override
  public String getShortName() {
    return "proguard_obfuscation";
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    AndroidPlatformTarget androidPlatformTarget = context.getAndroidPlatformTarget();
    Joiner pathJoiner = Joiner.on(':');

    // Run ProGuard as a standalone executable JAR file.
    String proguardJar = androidPlatformTarget.getProguardJar().getAbsolutePath();
    args.add("java").add("-Xmx1024M").add("-jar").add(proguardJar);

    // -include
    if (useAndroidProguardConfigWithOptimizations) {
      args.add("-include")
          .add(androidPlatformTarget.getOptimizedProguardConfig().getAbsolutePath());
    } else {
      args.add("-include").add(androidPlatformTarget.getProguardConfig().getAbsolutePath());
    }
    for (String proguardConfig : customProguardConfigs) {
      args.add("-include").add(proguardConfig);
    }
    args.add("-include").add(generatedProGuardConfig);

    // -injars and -outjars paired together for each input.
    for (Map.Entry<String, String> inputOutputEntry : inputAndOutputEntries.entrySet()) {
      args.add("-injars").add(inputOutputEntry.getKey());
      args.add("-outjars").add(inputOutputEntry.getValue());
    }

    // -libraryjars
    Iterable<String> bootclasspathPaths = Iterables.transform(
        androidPlatformTarget.getBootclasspathEntries(), Functions.FILE_TO_ABSOLUTE_PATH);
    Iterable<String> libraryJars = Iterables.concat(bootclasspathPaths,
        additionalLibraryJarsForProguard);
    args.add("-libraryjars").add(pathJoiner.join(libraryJars));

    // -dump
    args.add("-dump").add(proguardDirectory + "/dump.txt");
    args.add("-printseeds").add(proguardDirectory + "/seeds.txt");
    args.add("-printusage").add(proguardDirectory + "/usage.txt");
    args.add("-printmapping").add(proguardDirectory + "/mapping.txt");
    args.add("-printconfiguration").add(proguardDirectory + "/configuration.txt");

    return args.build();
  }

  @Override
  public int execute(ExecutionContext context) {
    int exitCode = super.execute(context);

    // proguard has a peculiar behaviour when multiple -injars/outjars pairs are specified in which
    // any -injars that would have been fully stripped away will not produce their matching -outjars
    // as requested (so the file won't exist).  Our build steps are not sophisticated enough to
    // account for this and remove those entries from the classes to dex so we hack things here to
    // ensure that the files exist but are empty.
    if (exitCode == 0) {
      ensureAllOutputsExist();
    }

    return exitCode;
  }

  private void ensureAllOutputsExist() {
    for (String outputJar : inputAndOutputEntries.values()) {
      File outputJarFile = new File(outputJar);
      if (!outputJarFile.exists()) {
        try {
          createEmptyZip(outputJarFile);
        } catch (IOException e) {
          throw new HumanReadableException("Failed to create empty jar file: %s", outputJar);
        }
      }
    }
  }

  @VisibleForTesting
  static void createEmptyZip(File file) throws IOException {
    Files.createParentDirs(file);
    ZipOutputStream out = new ZipOutputStream(new FileOutputStream(file));
    // Sun's java 6 runtime doesn't allow us to create a truly empty zip, but this should be enough
    // to pass through dx/split-zip without any issue.
    // ...and Sun's java 7 runtime doesn't let us use an empty string for the zip entry name.
    out.putNextEntry(new ZipEntry("proguard_no_result"));
    out.close();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == null || !(obj instanceof ProGuardObfuscateStep)) {
      return false;
    }
    ProGuardObfuscateStep that = (ProGuardObfuscateStep) obj;

    return
        Objects.equal(useAndroidProguardConfigWithOptimizations,
            that.useAndroidProguardConfigWithOptimizations) &&
        Objects.equal(additionalLibraryJarsForProguard,
            that.additionalLibraryJarsForProguard) &&
        Objects.equal(customProguardConfigs, that.customProguardConfigs) &&
        Objects.equal(generatedProGuardConfig, that.generatedProGuardConfig) &&
        Objects.equal(inputAndOutputEntries, that.inputAndOutputEntries) &&
        Objects.equal(proguardDirectory, that.proguardDirectory);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(useAndroidProguardConfigWithOptimizations,
        additionalLibraryJarsForProguard,
        customProguardConfigs,
        generatedProGuardConfig,
        inputAndOutputEntries,
        proguardDirectory);
  }
}
