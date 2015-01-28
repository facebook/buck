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
import com.facebook.buck.util.VersionStringComparator;

import com.google.common.annotations.VisibleForTesting;
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

  public static final String DEFAULT_ANDROID_PLATFORM_TARGET = "Google Inc.:Google APIs:21";
  public static final String ANDROID_VERSION_PREFIX = "android-";

  @VisibleForTesting
  static final Pattern PLATFORM_TARGET_PATTERN = Pattern.compile(
      "(?:Google Inc\\.:Google APIs:|android-)(\\d+)");

  private final String name;
  private final Path androidJar;
  private final List<Path> bootclasspathEntries;
  private final Path aaptExecutable;
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

  /**
   * @return bootclasspath entries as absolute {@link Path}s
   */
  public List<Path> getBootclasspathEntries() {
    return bootclasspathEntries;
  }

  public Path getAaptExecutable() {
    return aaptExecutable;
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
    return androidDirectoryResolver.findAndroidNdkDir();
  }

  /**
   * @param platformId for the platform, such as "Google Inc.:Google APIs:16"
   */
  public static Optional<AndroidPlatformTarget> getTargetForId(
      String platformId,
      AndroidDirectoryResolver androidDirectoryResolver,
      Optional<Path> aaptOverride) {

    Matcher platformMatcher = PLATFORM_TARGET_PATTERN.matcher(platformId);
    if (platformMatcher.matches()) {
      try {
        int apiLevel = Integer.parseInt(platformMatcher.group(1));
        Factory platformTargetFactory;
        if (platformId.contains("Google APIs")) {
          platformTargetFactory = new AndroidWithGoogleApisFactory();
        } else {
          platformTargetFactory = new AndroidWithoutGoogleApisFactory();
        }
        return Optional.of(
            platformTargetFactory.newInstance(androidDirectoryResolver, apiLevel, aaptOverride));
      } catch (NumberFormatException e) {
        return Optional.absent();
      }
    } else {
      return Optional.absent();
    }
  }

  public static AndroidPlatformTarget getDefaultPlatformTarget(
      AndroidDirectoryResolver androidDirectoryResolver,
      Optional<Path> aaptOverride) {
    return getTargetForId(DEFAULT_ANDROID_PLATFORM_TARGET, androidDirectoryResolver, aaptOverride)
        .get();
  }

  private static interface Factory {
    public AndroidPlatformTarget newInstance(
        AndroidDirectoryResolver androidDirectoryResolver,
        int apiLevel,
        Optional<Path> aaptOverride);
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
      Set<Path> additionalJarPaths,
      Optional<Path> aaptOverride) {
    Path androidSdkDir = androidDirectoryResolver.findAndroidSdkDir();
    if (!androidSdkDir.isAbsolute()) {
      throw new HumanReadableException(
          "Path to Android SDK must be absolute but was: %s.",
          androidSdkDir);
    }

    Path platformDirectory = androidSdkDir.resolve(platformDirectoryPath);
    Path androidJar = platformDirectory.resolve("android.jar");
    LinkedList<Path> bootclasspathEntries = Lists.newLinkedList(additionalJarPaths);

    // Make sure android.jar is at the front of the bootclasspath.
    bootclasspathEntries.addFirst(androidJar);

    Path buildToolsDir = androidSdkDir.resolve("build-tools");

    // This is the relative path under the Android SDK directory to the directory that contains the
    // aapt, aidl, and dx executables.
    String buildToolsPath;

    if (buildToolsDir.toFile().isDirectory()) {
      // In older versions of the ADT that have been upgraded via the SDK manager, the build-tools
      // directory appears to contain subfolders of the form "17.0.0". However, newer versions of
      // the ADT that are downloaded directly from http://developer.android.com/ appear to have
      // subfolders of the form android-4.2.2. We need to support both of these scenarios.
      File[] directories = buildToolsDir.toFile().listFiles(new FileFilter() {
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
            buildToolsDir,
            androidSdkDir.resolve("tools").resolve("android"));
      } else {
        File newestBuildToolsDir = pickNewestBuildToolsDir(ImmutableSet.copyOf(directories));
        buildToolsPath = "build-tools/" + newestBuildToolsDir.getName();
      }
    } else {
      buildToolsPath = "platform-tools";
    }

    Path zipAlignExecutable = androidSdkDir.resolve("tools/zipalign").toAbsolutePath();
    if (!zipAlignExecutable.toFile().exists()) {
      // Android SDK Build-tools >= 19.1.0 have zipalign under the build-tools directory.
      zipAlignExecutable =
          androidSdkDir.resolve(buildToolsPath).resolve("zipalign").toAbsolutePath();
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
        aaptOverride.or(androidSdkDir.resolve(buildToolsPath).resolve("aapt").toAbsolutePath()),
        androidSdkDir.resolve("platform-tools/adb").toAbsolutePath(),
        androidSdkDir.resolve(buildToolsPath).resolve("aidl").toAbsolutePath(),
        zipAlignExecutable,
        androidSdkDir.resolve(buildToolsPath).resolve("dx").toAbsolutePath(),
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

    List<File> apiVersionDirectories = Lists.newArrayList();
    List<File> androidVersionDirectories = Lists.newArrayList();
    for (File dir : directories) {
      if (dir.getName().startsWith(ANDROID_VERSION_PREFIX)) {
        androidVersionDirectories.add(dir);
      } else {
        apiVersionDirectories.add(dir);
      }
    }

    final VersionStringComparator comparator = new VersionStringComparator();

    // API version directories are downloaded by the package manager, whereas Android version
    // directories are bundled with the SDK when it's unpacked. So API version directories will
    // presumably be newer.
    if (!apiVersionDirectories.isEmpty()) {
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
    } else {
      Collections.sort(androidVersionDirectories, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
              String versionA = a.getName().substring(ANDROID_VERSION_PREFIX.length());
              String versionB = b.getName().substring(ANDROID_VERSION_PREFIX.length());
              return comparator.compare(versionA, versionB);
        }
      });
      // Return the last element in the list.
      return androidVersionDirectories.get(androidVersionDirectories.size() - 1);
    }
  }

  /**
   * Factory to build an AndroidPlatformTarget that corresponds to a given Google API level.
   */
  private static class AndroidWithGoogleApisFactory implements Factory {

    private static final String API_DIR_SUFFIX = "(?:-([0-9]+))*";

    @Override
    public AndroidPlatformTarget newInstance(
        final AndroidDirectoryResolver androidDirectoryResolver,
        final int apiLevel,
        Optional<Path> aaptOverride) {
      // TODO(natthu): Use Paths instead of Strings everywhere in this file.
      Path androidSdkDir = androidDirectoryResolver.findAndroidSdkDir();
      File addonsParentDir = androidSdkDir.resolve("add-ons").toFile();
      String apiDirPrefix = String.format("addon-google_apis-google-%d", apiLevel);
      final Pattern apiDirPattern = Pattern.compile(apiDirPrefix + API_DIR_SUFFIX);

      if (addonsParentDir.isDirectory()) {
        String[] addonsApiDirs = addonsParentDir.list(
            new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return apiDirPattern.matcher(name).matches();
              }
            });
        Arrays.sort(addonsApiDirs, new Comparator<String>() {
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
          if (libsDir.isDirectory() &&
              (addonFiles = libsDir.list(new AddonFilter())) != null &&
              addonFiles.length != 0) {
            Arrays.sort(addonFiles);
            for (String addonJar : addonFiles) {
              additionalJarPaths.add(libsDir.toPath().resolve(addonJar));
            }

            return createFromDefaultDirectoryStructure(
                String.format("Google Inc.:Google APIs:%d", apiLevel),
                androidDirectoryResolver,
                String.format("platforms/android-%d", apiLevel),
                additionalJarPaths.build(),
                aaptOverride);
          }
        }
      }

      throw new HumanReadableException(
          "Google APIs not found in %s.\n" +
          "Please run '%s/tools/android sdk' and select both 'SDK Platform' and " +
          "'Google APIs' under Android (API %d)",
          new File(addonsParentDir, apiDirPrefix + "/libs").getAbsolutePath(),
          androidSdkDir,
          apiLevel);
    }
  }

  private static class AndroidWithoutGoogleApisFactory implements Factory {
    @Override
    public AndroidPlatformTarget newInstance(
        final AndroidDirectoryResolver androidDirectoryResolver,
        final int apiLevel,
        Optional<Path> aaptOverride) {
      return createFromDefaultDirectoryStructure(
          String.format("android-%d", apiLevel),
          androidDirectoryResolver,
          String.format("platforms/android-%d", apiLevel),
          /* additionalJarPaths */ ImmutableSet.<Path>of(),
          aaptOverride);
    }
  }

  private static class AddonFilter implements FilenameFilter {

    @Override
    public boolean accept(File dir, String name) {
      return name.endsWith(".jar");
    }
  }
}
