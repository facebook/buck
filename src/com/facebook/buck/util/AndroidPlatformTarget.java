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

package com.facebook.buck.util;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileFilter;
import java.io.FilenameFilter;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a platform to target for Android. Eventually, it should be possible to construct an
 * arbitrary platform target, but currently, we only recognize a fixed set of targets.
 */
public class AndroidPlatformTarget {

  public static final String DEFAULT_ANDROID_PLATFORM_TARGET = "Google Inc.:Google APIs:19";

  private final String name;
  private final File androidJar;
  private final List<Path> bootclasspathEntries;
  private final File aaptExecutable;
  private final File adbExecutable;
  private final File aidlExecutable;
  private final File zipalignExecutable;
  private final File dxExecutable;
  private final File androidFrameworkIdlFile;
  private final File proguardJar;
  private final File proguardConfig;
  private final File optimizedProguardConfig;
  private final AndroidDirectoryResolver androidDirectoryResolver;


  private AndroidPlatformTarget(
      String name,
      File androidJar,
      List<Path> bootclasspathEntries,
      File aaptExecutable,
      File adbExecutable,
      File aidlExecutable,
      File zipalignExecutable,
      File dxExecutable,
      File androidFrameworkIdlFile,
      File proguardJar,
      File proguardConfig,
      File optimizedProguardConfig,
      AndroidDirectoryResolver androidDirectoryResolver) {
    this.name = Preconditions.checkNotNull(name);
    this.androidJar = Preconditions.checkNotNull(androidJar);
    this.bootclasspathEntries = ImmutableList.copyOf(bootclasspathEntries);
    this.aaptExecutable = Preconditions.checkNotNull(aaptExecutable);
    this.adbExecutable = Preconditions.checkNotNull(adbExecutable);
    this.aidlExecutable = Preconditions.checkNotNull(aidlExecutable);
    this.zipalignExecutable = Preconditions.checkNotNull(zipalignExecutable);
    this.dxExecutable = Preconditions.checkNotNull(dxExecutable);
    this.androidFrameworkIdlFile = Preconditions.checkNotNull(androidFrameworkIdlFile);
    this.proguardJar = Preconditions.checkNotNull(proguardJar);
    this.proguardConfig = Preconditions.checkNotNull(proguardConfig);
    this.optimizedProguardConfig = Preconditions.checkNotNull(optimizedProguardConfig);
    this.androidDirectoryResolver = Preconditions.checkNotNull(androidDirectoryResolver);
  }

  public String getName() {
    return name;
  }

  @Override
  public String toString() {
    return getName();
  }

  public File getAndroidJar() {
    return androidJar;
  }

  /**
   * @return bootclasspath entries as absolute {@link Path}s
   */
  public List<Path> getBootclasspathEntries() {
    return bootclasspathEntries;
  }

  public File getAaptExecutable() {
    return aaptExecutable;
  }

  public File getAdbExecutable() {
    return adbExecutable;
  }

  public File getAidlExecutable() {
    return aidlExecutable;
  }

  public File getZipalignExecutable() {
    return zipalignExecutable;
  }

  public File getDxExecutable() {
    return dxExecutable;
  }

  public File getAndroidFrameworkIdlFile() {
    return androidFrameworkIdlFile;
  }

  public File getProguardJar() {
    return proguardJar;
  }

  public File getProguardConfig() {
    return proguardConfig;
  }

  public File getOptimizedProguardConfig() {
    return optimizedProguardConfig;
  }

  public Optional<Path> getNdkDirectory() {
    return androidDirectoryResolver.findAndroidNdkDir();
  }

  /**
   * @param platformId for the platform, such as "Google Inc.:Google APIs:16"
   */
  public static Optional<AndroidPlatformTarget> getTargetForId(
      String platformId,
      AndroidDirectoryResolver androidDirectoryResolver) {
    Preconditions.checkNotNull(platformId);
    Preconditions.checkNotNull(androidDirectoryResolver);

    Pattern platformPattern = Pattern.compile("Google Inc\\.:Google APIs:(\\d+)");
    Matcher platformMatcher = platformPattern.matcher(platformId);
    if (platformMatcher.matches()) {
      try {
        int apiLevel = Integer.parseInt(platformMatcher.group(1));
        return Optional.of(
            new AndroidWithGoogleApisFactory().newInstance(androidDirectoryResolver, apiLevel));
      } catch (NumberFormatException e) {
        return Optional.absent();
      }
    } else {
      return Optional.absent();
    }
  }

  public static AndroidPlatformTarget getDefaultPlatformTarget(
      AndroidDirectoryResolver androidDirectoryResolver) {
    return getTargetForId(DEFAULT_ANDROID_PLATFORM_TARGET, androidDirectoryResolver).get();
  }

  private static interface Factory {
    public AndroidPlatformTarget newInstance(
        AndroidDirectoryResolver androidDirectoryResolver,
        int apiLevel);
  }

  /**
   * Resolves all of the jarPaths against the androidSdkDir path.
   * @return a mutable list
   */
  private static LinkedList<Path> resolvePaths(final File androidSdkDir, Set<String> jarPaths) {
    return Lists.newLinkedList(Iterables.transform(jarPaths, new Function<String, Path>() {
      @Override
      public Path apply(String jarPath) {
        File jar = new File(androidSdkDir, jarPath);
        if (!jar.isFile()) {
          throw new RuntimeException("File not found: " + jar.getAbsolutePath());
        }
        return jar.toPath();
      }
    }));
  }

  /**
   * Given the path to the Android SDK as well as the platform path within the Android SDK,
   * find all the files needed to create the {@link AndroidPlatformTarget}, assuming that the
   * organization of the Android SDK conforms to the ordinary directory structure.
   */
  @VisibleForTesting
  static AndroidPlatformTarget createFromDefaultDirectoryStructure(
      String name,
      AndroidDirectoryResolver androidDirectoryResolver,
      String platformDirectoryPath,
      Set<String> additionalJarPaths) {
    File androidSdkDir = androidDirectoryResolver.findAndroidSdkDir().toFile();
    if (!androidSdkDir.isAbsolute()) {
      throw new HumanReadableException(
          "Path to Android SDK must be absolute but was: %s.",
          androidSdkDir);
    }

    File platformDirectory = new File(androidSdkDir, platformDirectoryPath);
    File androidJar = new File(platformDirectory, "android.jar");
    LinkedList<Path> bootclasspathEntries = resolvePaths(androidSdkDir, additionalJarPaths);

    // Make sure android.jar is at the front of the bootclasspath.
    bootclasspathEntries.addFirst(androidJar.toPath());

    File buildToolsDir = new File(androidSdkDir, "build-tools");

    // This is the relative path under the Android SDK directory to the directory that contains the
    // aapt, aidl, and dx executables.
    String buildToolsPath;

    if (buildToolsDir.isDirectory()) {
      // In older versions of the ADT that have been upgraded via the SDK manager, the build-tools
      // directory appears to contain subfolders of the form "17.0.0". However, newer versions of
      // the ADT that are downloaded directly from http://developer.android.com/ appear to have
      // subfolders of the form android-4.2.2. We need to support both of these scenarios.
      File[] directories = buildToolsDir.listFiles(new FileFilter() {
        @Override
        public boolean accept(File pathname) {
          return pathname.isDirectory();
        }
      });

      if (directories.length == 0) {
        throw new HumanReadableException(
          Joiner.on(System.getProperty("line.separator")).join(
            "%s was empty, but should have contained a subdirectory with build tools.",
            "Install them using the Android SDK Manager (%s)."),
          buildToolsDir.getAbsolutePath(),
          new File(androidSdkDir, Joiner.on(File.separator).join("tools", "android"))
        );
      } else {
        File newestBuildToolsDir = pickNewestBuildToolsDir(ImmutableSet.copyOf(directories));
        buildToolsPath = "build-tools/" + newestBuildToolsDir.getName();
      }
    } else {
      buildToolsPath = "platform-tools";
    }

    File androidFrameworkIdlFile = new File(platformDirectory, "framework.aidl");
    File proguardJar = new File(androidSdkDir, "tools/proguard/lib/proguard.jar");
    File proguardConfig = new File(androidSdkDir, "tools/proguard/proguard-android.txt");
    File optimizedProguardConfig =
        new File(androidSdkDir, "tools/proguard/proguard-android-optimize.txt");

    return new AndroidPlatformTarget(
        name,
        androidJar,
        bootclasspathEntries,
        new File(androidSdkDir, buildToolsPath + "/aapt"),
        new File(androidSdkDir, "platform-tools/adb"),
        new File(androidSdkDir, buildToolsPath + "/aidl"),
        new File(androidSdkDir, "tools/zipalign"),
        new File(androidSdkDir, buildToolsPath + "/dx"),
        androidFrameworkIdlFile,
        proguardJar,
        proguardConfig,
        optimizedProguardConfig,
        androidDirectoryResolver);
  }

  private static File pickNewestBuildToolsDir(Set<File> directories) {
    if (directories.size() == 1) {
      return Iterables.getOnlyElement(directories);
    }

    List<File> androidVersionDirectories = Lists.newArrayList();
    List<File> apiVersionDirectories = Lists.newArrayList();
    for (File dir : directories) {
      if (dir.getName().startsWith("android-")) {
        androidVersionDirectories.add(dir);
      } else {
        apiVersionDirectories.add(dir);
      }
    }

    final VersionStringComparator comparator = new VersionStringComparator();

    // This is the directory from newer downloads from http://developer.android.com/, so prefer
    // these.
    if (!androidVersionDirectories.isEmpty()) {
      Collections.sort(androidVersionDirectories, new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
          String versionA = a.getName().substring("android-".length());
          String versionB = b.getName().substring("android-".length());
          return comparator.compare(versionA, versionB);
        }
      });
      // Return the last element in the list.
      return androidVersionDirectories.get(androidVersionDirectories.size() - 1);
    } else {
      Collections.sort(apiVersionDirectories, new Comparator<File>() {
        @Override
        public int compare(File a, File b) {
          String versionA = a.getName();
          String versionB = b.getName();
          return comparator.compare(versionA, versionB);
        }
      });
      // Return the last element in the list.
      return apiVersionDirectories.get(apiVersionDirectories.size() - 1);
    }
  }

  /**
   * Factory to build an AndroidPlatformTarget that corresponds to a given Google API level.
   */
  private static class AndroidWithGoogleApisFactory implements Factory {

    @Override
    public AndroidPlatformTarget newInstance(
        AndroidDirectoryResolver androidDirectoryResolver,
        int apiLevel) {
      String addonPath = String.format("/add-ons/addon-google_apis-google-%d/libs/", apiLevel);
      File androidSdkDir = androidDirectoryResolver.findAndroidSdkDir().toFile();
      File addonDirectory = new File(androidSdkDir.getPath() + addonPath);
      String[] addonFiles;

      if (!addonDirectory.isDirectory() ||
          (addonFiles = addonDirectory.list(new AddonFilter())) == null ||
          addonFiles.length == 0) {
        throw new HumanReadableException(
            "Google APIs not found in %s.\n" +
            "Please run '%s/tools/android sdk' and select both 'SDK Platform' and " +
            "'Google APIs' under Android (API %d)",
            addonDirectory.getAbsolutePath(),
            androidSdkDir.getPath(),
            apiLevel);
      }

      ImmutableSet.Builder<String> builder = ImmutableSet.builder();

      Arrays.sort(addonFiles);
      for (String filename : addonFiles) {
        builder.add(addonPath + filename);
      }
      Set<String> additionalJarPaths = builder.build();

      return createFromDefaultDirectoryStructure(
          String.format("Google Inc.:Google APIs:%d", apiLevel),
          androidDirectoryResolver,
          String.format("platforms/android-%d", apiLevel),
          additionalJarPaths);
    }
  }

  private static class AddonFilter implements FilenameFilter {

    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".jar");
    }
  }
}
