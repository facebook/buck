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

import com.android.common.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Merges resources into a final APK. This code is based off of the now deprecated apkbuilder tool:
 * https://android.googlesource.com/platform/sdk/+/fd30096196e3747986bdf8a95cc7713dd6e0b239%5E/sdkmanager/libs/sdklib/src/main/java/com/android/sdklib/build/ApkBuilderMain.java
 */
public class ApkBuilderStep implements Step {

  /**
   * The type of a keystore created via the {@code jarsigner} command in Sun/Oracle Java. See
   * http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore.
   */
  private static final String JARSIGNER_KEY_STORE_TYPE = "jks";

  private final ProjectFilesystem filesystem;
  private final Path resourceApk;
  private final Path dexFile;
  private final Path pathToOutputApkFile;
  private final ImmutableSet<Path> assetDirectories;
  private final ImmutableSet<Path> nativeLibraryDirectories;
  private final ImmutableSet<Path> zipFiles;
  private final ImmutableSet<Path> jarFilesThatMayContainResources;
  private final Path pathToKeystore;
  private final Supplier<KeystoreProperties> keystorePropertiesSupplier;
  private final boolean debugMode;
  private final ImmutableList<String> javaRuntimeLauncher;
  private final int apkCompressionLevel;

  /**
   * @param resourceApk Path to the Apk which only contains resources, no dex files.
   * @param pathToOutputApkFile Path to output our APK to.
   * @param dexFile Path to the classes.dex file.
   * @param assetDirectories List of paths to assets to be included in the apk.
   * @param nativeLibraryDirectories List of paths to native directories.
   * @param zipFiles List of paths to zipfiles to be included into the apk.
   * @param debugMode Whether or not to run ApkBuilder with debug mode turned on.
   * @param apkCompressionLevel
   */
  public ApkBuilderStep(
      ProjectFilesystem filesystem,
      Path resourceApk,
      Path pathToOutputApkFile,
      Path dexFile,
      ImmutableSet<Path> assetDirectories,
      ImmutableSet<Path> nativeLibraryDirectories,
      ImmutableSet<Path> zipFiles,
      ImmutableSet<Path> jarFilesThatMayContainResources,
      Path pathToKeystore,
      Supplier<KeystoreProperties> keystorePropertiesSupplier,
      boolean debugMode,
      ImmutableList<String> javaRuntimeLauncher,
      int apkCompressionLevel) {
    this.filesystem = filesystem;
    this.resourceApk = resourceApk;
    this.pathToOutputApkFile = pathToOutputApkFile;
    this.dexFile = dexFile;
    this.assetDirectories = assetDirectories;
    this.nativeLibraryDirectories = nativeLibraryDirectories;
    this.jarFilesThatMayContainResources = jarFilesThatMayContainResources;
    this.zipFiles = zipFiles;
    this.pathToKeystore = pathToKeystore;
    this.keystorePropertiesSupplier = keystorePropertiesSupplier;
    this.debugMode = debugMode;
    this.javaRuntimeLauncher = javaRuntimeLauncher;
    this.apkCompressionLevel = apkCompressionLevel;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context)
      throws IOException, InterruptedException {
    PrintStream output = null;
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      output = context.getStdOut();
    }

    try {
      PrivateKeyAndCertificate privateKeyAndCertificate = createKeystoreProperties();
      ApkBuilder builder =
          new ApkBuilder(
              filesystem.getPathForRelativePath(pathToOutputApkFile).toFile(),
              filesystem.getPathForRelativePath(resourceApk).toFile(),
              filesystem.getPathForRelativePath(dexFile).toFile(),
              privateKeyAndCertificate.privateKey,
              privateKeyAndCertificate.certificate,
              output,
              apkCompressionLevel);
      builder.setDebugMode(debugMode);
      for (Path nativeLibraryDirectory : nativeLibraryDirectories) {
        builder.addNativeLibraries(
            filesystem.getPathForRelativePath(nativeLibraryDirectory).toFile());
      }
      for (Path assetDirectory : assetDirectories) {
        builder.addSourceFolder(filesystem.getPathForRelativePath(assetDirectory).toFile());
      }
      for (Path zipFile : zipFiles) {
        // TODO(natthu): Skipping silently is bad. These should really be assertions.
        if (filesystem.exists(zipFile) && filesystem.isFile(zipFile)) {
          builder.addZipFile(filesystem.getPathForRelativePath(zipFile).toFile());
        }
      }
      for (Path jarFileThatMayContainResources : jarFilesThatMayContainResources) {
        Path jarFile = filesystem.getPathForRelativePath(jarFileThatMayContainResources);
        builder.addResourcesFromJar(jarFile.toFile());
      }

      // Build the APK
      builder.sealApk();
    } catch (ApkCreationException
        | KeyStoreException
        | NoSuchAlgorithmException
        | SealedApkException
        | UnrecoverableKeyException e) {
      context.logError(e, "Error when creating APK at: %s.", pathToOutputApkFile);
      return StepExecutionResults.ERROR;
    } catch (DuplicateFileException e) {
      throw new HumanReadableException(
          String.format(
              "Found duplicate file for APK: %1$s\nOrigin 1: %2$s\nOrigin 2: %3$s",
              e.getArchivePath(), e.getFile1(), e.getFile2()));
    }
    return StepExecutionResults.SUCCESS;
  }

  private PrivateKeyAndCertificate createKeystoreProperties()
      throws IOException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
    KeyStore keystore = KeyStore.getInstance(JARSIGNER_KEY_STORE_TYPE);
    KeystoreProperties keystoreProperties = keystorePropertiesSupplier.get();
    char[] keystorePassword = keystoreProperties.getStorepass().toCharArray();
    try {
      keystore.load(filesystem.getInputStreamForRelativePath(pathToKeystore), keystorePassword);
    } catch (NoSuchAlgorithmException | CertificateException e) {
      throw new HumanReadableException(e, "%s is an invalid keystore.", pathToKeystore);
    }

    String alias = keystoreProperties.getAlias();
    char[] keyPassword = keystoreProperties.getKeypass().toCharArray();
    Key key = keystore.getKey(alias, keyPassword);
    // key can be null if alias/password is incorrect.
    if (key == null) {
      throw new HumanReadableException(
          "The keystore [%s] key.alias [%s] does not exist or does not identify a key-related "
              + "entry",
          pathToKeystore, alias);
    }

    Certificate certificate = keystore.getCertificate(alias);

    return new PrivateKeyAndCertificate((PrivateKey) key, (X509Certificate) certificate);
  }

  @Override
  public String getShortName() {
    return "apk_builder";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.addAll(javaRuntimeLauncher);
    args.add(
        "-classpath",
        // TODO(mbolin): Make the directory that corresponds to $ANDROID_HOME a field that is
        // accessible via an AndroidPlatformTarget and insert that here in place of "$ANDROID_HOME".
        "$ANDROID_HOME/tools/lib/sdklib.jar",
        "com.android.sdklib.build.ApkBuilderMain");
    args.add(String.valueOf(pathToOutputApkFile));
    args.add("-v" /* verbose */);
    if (debugMode) {
      args.add("-d");
    }

    // Unfortunately, ApkBuilderMain does not have CLI args to set the keystore,
    // so these member variables are left out of the command:
    // pathToKeystore, pathToKeystorePropertiesFile

    Multimap<String, Collection<Path>> groups =
        ImmutableMultimap.<String, Collection<Path>>builder()
            .put("-z", ImmutableList.of(resourceApk))
            .put("-f", ImmutableList.of(dexFile))
            .put("-rf", assetDirectories)
            .put("-nf", nativeLibraryDirectories)
            .put("-z", zipFiles)
            .put("-rj", jarFilesThatMayContainResources)
            .build();

    for (Map.Entry<String, Collection<Path>> group : groups.entries()) {
      String prefix = group.getKey();
      for (Path path : group.getValue()) {
        args.add(prefix, String.valueOf(path));
      }
    }

    return Joiner.on(' ').join(args.build());
  }

  private static class PrivateKeyAndCertificate {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    PrivateKeyAndCertificate(PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = privateKey;
      this.certificate = certificate;
    }
  }
}
