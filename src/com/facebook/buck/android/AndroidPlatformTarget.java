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

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import java.io.File;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a platform to target for Android. Eventually, it should be possible to construct an
 * arbitrary platform target, but currently, we only recognize a fixed set of targets.
 */
public class AndroidPlatformTarget {

  public static final String DEFAULT_ANDROID_PLATFORM_TARGET = "android-23";

  /** {@link Supplier} for an {@link AndroidPlatformTarget} that always throws. */
  public static final Supplier<AndroidPlatformTarget> EXPLODING_ANDROID_PLATFORM_TARGET_SUPPLIER =
      () -> {
        throw new HumanReadableException(
            "Must set ANDROID_SDK to point to the absolute path of your Android SDK directory.");
      };

  @VisibleForTesting
  static final Pattern PLATFORM_TARGET_PATTERN =
      Pattern.compile("(?:Google Inc\\.:Google APIs:|android-)(.+)");

  private final String name;
  private final Path androidJar;
  private final List<Path> bootclasspathEntries;
  private final Path aaptExecutable;
  private final Path aapt2Executable;
  private final Path adbExecutable;
  private final Path aidlExecutable;
  private final Path zipalignExecutable;
  private final Path dxExecutable;
  private final Path androidFrameworkIdlFile;
  private final Path proguardJar;
  private final Path proguardConfig;
  private final Path optimizedProguardConfig;
  private final AndroidDirectoryResolver androidDirectoryResolver;

  private AndroidPlatformTarget(
      String name,
      Path androidJar,
      List<Path> bootclasspathEntries,
      Path aaptExecutable,
      Path aapt2Executable,
      Path adbExecutable,
      Path aidlExecutable,
      Path zipalignExecutable,
      Path dxExecutable,
      Path androidFrameworkIdlFile,
      Path proguardJar,
      Path proguardConfig,
      Path optimizedProguardConfig,
      AndroidDirectoryResolver androidDirectoryResolver) {
    this.name = name;
    this.androidJar = androidJar;
    this.bootclasspathEntries = ImmutableList.copyOf(bootclasspathEntries);
    this.aaptExecutable = aaptExecutable;
    this.aapt2Executable = aapt2Executable;
    this.adbExecutable = adbExecutable;
    this.aidlExecutable = aidlExecutable;
    this.zipalignExecutable = zipalignExecutable;
    this.dxExecutable = dxExecutable;
    this.androidFrameworkIdlFile = androidFrameworkIdlFile;
    this.proguardJar = proguardJar;
    this.proguardConfig = proguardConfig;
    this.optimizedProguardConfig = optimizedProguardConfig;
    this.androidDirectoryResolver = androidDirectoryResolver;
  }

  /** This is likely something like {@code "Google Inc.:Google APIs:21"}. */
  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getName();
  }

  public Path getAndroidJar() {
    return androidJar;
  }

  /** @return bootclasspath entries as absolute {@link Path}s */
  public List<Path> getBootclasspathEntries() {
    return bootclasspathEntries;
  }

  public Path getAaptExecutable() {
    return aaptExecutable;
  }

  public Path getAapt2Executable() {
    return aapt2Executable;
  }

  public Path getAdbExecutable() {
    return adbExecutable;
  }

  public Path getAidlExecutable() {
    return aidlExecutable;
  }

  public Path getZipalignExecutable() {
    return zipalignExecutable;
  }

  public Path getDxExecutable() {
    return dxExecutable;
  }

  public Path getAndroidFrameworkIdlFile() {
    return androidFrameworkIdlFile;
  }

  public Path getProguardJar() {
    return proguardJar;
  }

  public Path getProguardConfig() {
    return proguardConfig;
  }

  public Path getOptimizedProguardConfig() {
    return optimizedProguardConfig;
  }

  public Optional<Path> getNdkDirectory() {
    return androidDirectoryResolver.getNdkOrAbsent();
  }

  public Path checkNdkDirectory() {
    return androidDirectoryResolver.getNdkOrThrow();
  }

  public Optional<Path> getSdkDirectory() {
    return androidDirectoryResolver.getSdkOrAbsent();
  }

  public Path checkSdkDirectory() {
    return androidDirectoryResolver.getSdkOrThrow();
  }

  /** @param platformId for the platform, such as "Google Inc.:Google APIs:16" */
  public static AndroidPlatformTarget getTargetForId(
      String platformId,
      AndroidDirectoryResolver androidDirectoryResolver,
      Optional<Path> aaptOverride,
      Optional<Path> aapt2Override) {

    Matcher platformMatcher = PLATFORM_TARGET_PATTERN.matcher(platformId);
    if (platformMatcher.matches()) {
      String apiLevel = platformMatcher.group(1);
      Factory platformTargetFactory;
      if (platformId.contains("Google APIs")) {
        platformTargetFactory = new AndroidWithGoogleApisFactory();
      } else {
        platformTargetFactory = new AndroidWithoutGoogleApisFactory();
      }
      return platformTargetFactory.newInstance(
          androidDirectoryResolver, apiLevel, aaptOverride, aapt2Override);
    } else {
      String messagePrefix =
          String.format("The Android SDK for '%s' could not be found. ", platformId);
      throw new HumanReadableException(
          messagePrefix
              + "Must set ANDROID_SDK to point to the absolute path of your Android SDK directory.");
    }
  }

  public static AndroidPlatformTarget getDefaultPlatformTarget(
      AndroidDirectoryResolver androidDirectoryResolver,
      Optional<Path> aaptOverride,
      Optional<Path> aapt2Override) {
    return getTargetForId(
        DEFAULT_ANDROID_PLATFORM_TARGET, androidDirectoryResolver, aaptOverride, aapt2Override);
  }

  private interface Factory {
    AndroidPlatformTarget newInstance(
        AndroidDirectoryResolver androidDirectoryResolver,
        String apiLevel,
        Optional<Path> aaptOverride,
        Optional<Path> aapt2Override);
  }

  /**
   * Given the path to the Android SDK as well as the platform path within the Android SDK, find all
   * the files needed to create the {@link AndroidPlatformTarget}, assuming that the organization of
   * the Android SDK conforms to the ordinary directory structure.
   */
  @VisibleForTesting
  static AndroidPlatformTarget createFromDefaultDirectoryStructure(
      String name,
      AndroidDirectoryResolver androidDirectoryResolver,
      String platformDirectoryPath,
      Set<Path> additionalJarPaths,
      Optional<Path> aaptOverride,
      Optional<Path> aapt2Override) {
    Path androidSdkDir = androidDirectoryResolver.getSdkOrThrow();
    if (!androidSdkDir.isAbsolute()) {
      throw new HumanReadableException(
          "Path to Android SDK must be absolute but was: %s.", androidSdkDir);
    }

    Path platformDirectory = androidSdkDir.resolve(platformDirectoryPath);
    Path androidJar = platformDirectory.resolve("android.jar");

    // Add any libraries found in the optional directory under the Android SDK directory. These
    // go at the head of the bootclasspath before any additional jars.
    File optionalDirectory = platformDirectory.resolve("optional").toFile();
    if (optionalDirectory.exists() && optionalDirectory.isDirectory()) {
      String[] optionalDirList = optionalDirectory.list(new AddonFilter());
      if (optionalDirList != null) {
        Arrays.sort(optionalDirList);
        ImmutableSet.Builder<Path> additionalJars = ImmutableSet.builder();
        for (String file : optionalDirList) {
          additionalJars.add(optionalDirectory.toPath().resolve(file));
        }
        additionalJars.addAll(additionalJarPaths);
        additionalJarPaths = additionalJars.build();
      }
    }

    LinkedList<Path> bootclasspathEntries = Lists.newLinkedList(additionalJarPaths);

    // Make sure android.jar is at the front of the bootclasspath.
    bootclasspathEntries.addFirst(androidJar);

    // This is the directory under the Android SDK directory that contains the dx script, jack,
    // jill, and binaries.
    Path buildToolsDir = androidDirectoryResolver.getBuildToolsOrThrow();

    // This is the directory under the Android SDK directory that contains the aapt, aidl, and
    // zipalign binaries. Before Android SDK Build-tools 23.0.0_rc1, this was the same as
    // buildToolsDir above.
    Path buildToolsBinDir;
    if (buildToolsDir.resolve("bin").toFile().exists()) {
      // Android SDK Build-tools >= 23.0.0_rc1 have executables under a new bin directory.
      buildToolsBinDir = buildToolsDir.resolve("bin");
    } else {
      // Android SDK Build-tools < 23.0.0_rc1 have executables under the build-tools directory.
      buildToolsBinDir = buildToolsDir;
    }

    Path zipAlignExecutable = androidSdkDir.resolve("tools/zipalign").toAbsolutePath();
    if (!zipAlignExecutable.toFile().exists()) {
      // Android SDK Build-tools >= 19.1.0 have zipalign under the build-tools directory.
      zipAlignExecutable =
          androidSdkDir.resolve(buildToolsBinDir).resolve("zipalign").toAbsolutePath();
    }

    Path androidFrameworkIdlFile = platformDirectory.resolve("framework.aidl");
    Path proguardJar = androidSdkDir.resolve("tools/proguard/lib/proguard.jar");
    Path proguardConfig = androidSdkDir.resolve("tools/proguard/proguard-android.txt");
    Path optimizedProguardConfig =
        androidSdkDir.resolve("tools/proguard/proguard-android-optimize.txt");

    return new AndroidPlatformTarget(
        name,
        androidJar.toAbsolutePath(),
        bootclasspathEntries,
        aaptOverride.orElse(
            androidSdkDir.resolve(buildToolsBinDir).resolve("aapt").toAbsolutePath()),
        aapt2Override.orElse(
            androidSdkDir.resolve(buildToolsBinDir).resolve("aapt2").toAbsolutePath()),
        androidSdkDir.resolve("platform-tools/adb").toAbsolutePath(),
        androidSdkDir.resolve(buildToolsBinDir).resolve("aidl").toAbsolutePath(),
        zipAlignExecutable,
        buildToolsDir
            .resolve(Platform.detect() == Platform.WINDOWS ? "dx.bat" : "dx")
            .toAbsolutePath(),
        androidFrameworkIdlFile,
        proguardJar,
        proguardConfig,
        optimizedProguardConfig,
        androidDirectoryResolver);
  }

  /** Factory to build an AndroidPlatformTarget that corresponds to a given Google API level. */
  private static class AndroidWithGoogleApisFactory implements Factory {

    private static final String API_DIR_SUFFIX = "(?:-([0-9]+))*";

    @Override
    public AndroidPlatformTarget newInstance(
        final AndroidDirectoryResolver androidDirectoryResolver,
        final String apiLevel,
        Optional<Path> aaptOverride,
        Optional<Path> aapt2Override) {
      // TODO(natthu): Use Paths instead of Strings everywhere in this file.
      Path androidSdkDir = androidDirectoryResolver.getSdkOrThrow();
      File addonsParentDir = androidSdkDir.resolve("add-ons").toFile();
      String apiDirPrefix = "addon-google_apis-google-" + apiLevel;
      final Pattern apiDirPattern = Pattern.compile(apiDirPrefix + API_DIR_SUFFIX);

      if (addonsParentDir.isDirectory()) {
        String[] addonsApiDirs =
            addonsParentDir.list((dir, name1) -> apiDirPattern.matcher(name1).matches());
        Arrays.sort(
            addonsApiDirs,
            new Comparator<String>() {
              @Override
              public int compare(String o1, String o2) {
                return getVersion(o1) - getVersion(o2);
              }

              private int getVersion(String dirName) {
                Matcher matcher = apiDirPattern.matcher(dirName);
                Preconditions.checkState(matcher.matches());
                if (matcher.group(1) != null) {
                  return Integer.parseInt(matcher.group(1));
                }
                return 0;
              }
            });

        ImmutableSet.Builder<Path> additionalJarPaths = ImmutableSet.builder();
        for (String dir : addonsApiDirs) {
          File libsDir = new File(addonsParentDir, dir + "/libs");

          String[] addonFiles;
          if (libsDir.isDirectory()
              && (addonFiles = libsDir.list(new AddonFilter())) != null
              && addonFiles.length != 0) {
            Arrays.sort(addonFiles);
            for (String addonJar : addonFiles) {
              additionalJarPaths.add(libsDir.toPath().resolve(addonJar));
            }

            return createFromDefaultDirectoryStructure(
                "Google Inc.:Google APIs:" + apiLevel,
                androidDirectoryResolver,
                "platforms/android-" + apiLevel,
                additionalJarPaths.build(),
                aaptOverride,
                aapt2Override);
          }
        }
      }

      throw new HumanReadableException(
          "Google APIs not found in %s.\n"
              + "Please run '%s/tools/android sdk' and select both 'SDK Platform' and "
              + "'Google APIs' under Android (API %s)",
          new File(addonsParentDir, apiDirPrefix + "/libs").getAbsolutePath(),
          androidSdkDir,
          apiLevel);
    }
  }

  private static class AndroidWithoutGoogleApisFactory implements Factory {
    @Override
    public AndroidPlatformTarget newInstance(
        final AndroidDirectoryResolver androidDirectoryResolver,
        final String apiLevel,
        Optional<Path> aaptOverride,
        Optional<Path> aapt2Override) {
      return createFromDefaultDirectoryStructure(
          "android-" + apiLevel,
          androidDirectoryResolver,
          "platforms/android-" + apiLevel,
          /* additionalJarPaths */ ImmutableSet.of(),
          aaptOverride,
          aapt2Override);
    }
  }

  private static class AddonFilter implements FilenameFilter {

    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".jar");
    }
  }
}
