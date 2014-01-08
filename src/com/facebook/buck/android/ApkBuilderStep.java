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

import com.android.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.KeystoreProperties;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * Merges resources into a final APK.  This code is based off of the now deprecated apkbuilder tool:
 * https://android.googlesource.com/platform/sdk/+/fd30096196e3747986bdf8a95cc7713dd6e0b239%5E/sdkmanager/libs/sdklib/src/main/java/com/android/sdklib/build/ApkBuilderMain.java
 */
public class ApkBuilderStep implements Step {

  /**
   * The type of a keystore created via the {@code jarsigner} command in Sun/Oracle Java.
   * See http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore.
   */
  private static final String JARSIGNER_KEY_STORE_TYPE = "jks";

  private final Path resourceApk;
  private final Path dexFile;
  private final Path pathToOutputApkFile;
  private final ImmutableSet<String> assetDirectories;
  private final ImmutableSet<Path> nativeLibraryDirectories;
  private final ImmutableSet<Path> zipFiles;
  private final ImmutableSet<String> jarFilesThatMayContainResources;
  private final Path pathToKeystore;
  private final Path pathToKeystorePropertiesFile;
  private final boolean debugMode;

  /**
   *
   * @param resourceApk Path to the Apk which only contains resources, no dex files.
   * @param pathToOutputApkFile Path to output our APK to.
   * @param dexFile Path to the classes.dex file.
   * @param javaResourcesDirectories List of paths to resources to be included in the apk.
   * @param nativeLibraryDirectories List of paths to native directories.
   * @param zipFiles List of paths to zipfiles to be included into the apk.
   * @param debugMode Whether or not to run ApkBuilder with debug mode turned on.
   * @param pathToKeystore Path to the keystore used to sign the APK.
   * @param pathToKeystorePropertiesFile Path to a {@code .properties} file that contains
   *     information about the keystore used to sign the APK.
   */
  public ApkBuilderStep(
      Path resourceApk,
      Path pathToOutputApkFile,
      Path dexFile,
      ImmutableSet<String> javaResourcesDirectories,
      ImmutableSet<Path> nativeLibraryDirectories,
      ImmutableSet<Path> zipFiles,
      ImmutableSet<String> jarFilesThatMayContainResources,
      Path pathToKeystore,
      Path pathToKeystorePropertiesFile,
      boolean debugMode) {
    this.resourceApk = Preconditions.checkNotNull(resourceApk);
    this.pathToOutputApkFile = Preconditions.checkNotNull(pathToOutputApkFile);
    this.dexFile = Preconditions.checkNotNull(dexFile);
    this.assetDirectories = Preconditions.checkNotNull(javaResourcesDirectories);
    this.nativeLibraryDirectories = Preconditions.checkNotNull(nativeLibraryDirectories);
    this.jarFilesThatMayContainResources =
        Preconditions.checkNotNull(jarFilesThatMayContainResources);
    this.zipFiles = Preconditions.checkNotNull(zipFiles);
    this.pathToKeystore = Preconditions.checkNotNull(pathToKeystore);
    this.pathToKeystorePropertiesFile = Preconditions.checkNotNull(pathToKeystorePropertiesFile);
    this.debugMode = debugMode;
  }

  @Override
  public int execute(ExecutionContext context) {
    PrintStream output = null;
    if (context.getVerbosity().shouldUseVerbosityFlagIfAvailable()) {
      output = context.getStdOut();
    }

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    try {
      PrivateKeyAndCertificate privateKeyAndCertificate = createKeystoreProperties(context);
      ApkBuilder builder = new ApkBuilder(
          projectFilesystem.getFileForRelativePath(pathToOutputApkFile),
          projectFilesystem.getFileForRelativePath(resourceApk),
          projectFilesystem.getFileForRelativePath(dexFile),
          privateKeyAndCertificate.privateKey,
          privateKeyAndCertificate.certificate,
          output);
      builder.setDebugMode(debugMode);
      for (Path nativeLibraryDirectory : nativeLibraryDirectories) {
        builder.addNativeLibraries(projectFilesystem.getFileForRelativePath(nativeLibraryDirectory));
      }
      for (String assetDirectory : assetDirectories) {
        builder.addSourceFolder(projectFilesystem.getFileForRelativePath(assetDirectory));
      }
      for (Path zipFile : zipFiles) {
        File zipFileOnDisk = projectFilesystem.getFileForRelativePath(zipFile);
        if (zipFileOnDisk.exists() && zipFileOnDisk.isFile()) {
          builder.addZipFile(zipFileOnDisk);
        }
      }
      for (String jarFileThatMayContainResources : jarFilesThatMayContainResources) {
        File jarFile  = projectFilesystem.getFileForRelativePath(jarFileThatMayContainResources);
        builder.addResourcesFromJar(jarFile);
      }

      // Build the APK
      builder.sealApk();
    } catch (ApkCreationException
        | CertificateException
        | IOException
        | KeyStoreException
        | NoSuchAlgorithmException
        | SealedApkException
        | UnrecoverableKeyException e) {
      context.logError(e, "Error when creating APK at: %s.", pathToOutputApkFile);
      return 1;
    } catch (DuplicateFileException e) {
      throw new HumanReadableException(
          String.format("Found duplicate file for APK: %1$s\nOrigin 1: %2$s\nOrigin 2: %3$s",
              e.getArchivePath(), e.getFile1(), e.getFile2()));
    }
    return 0;
  }

  private PrivateKeyAndCertificate createKeystoreProperties(ExecutionContext context)
      throws CertificateException,
          IOException,
          KeyStoreException,
          NoSuchAlgorithmException,
          UnrecoverableKeyException {
    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    KeystoreProperties keystoreProperties = KeystoreProperties.createFromPropertiesFile(
        pathToKeystore,
        pathToKeystorePropertiesFile,
        projectFilesystem);
    KeyStore keystore = KeyStore.getInstance(JARSIGNER_KEY_STORE_TYPE);
    InputStream inputStream = projectFilesystem.getInputSupplierForRelativePath(pathToKeystore)
        .getInput();
    char[] keystorePassword = keystoreProperties.getStorepass().toCharArray();
    keystore.load(inputStream, keystorePassword);

    String alias = keystoreProperties.getAlias();
    char[] keyPassword = keystoreProperties.getKeypass().toCharArray();
    Key key = keystore.getKey(alias, keyPassword);
    Certificate certificate = keystore.getCertificate(alias);

    return new PrivateKeyAndCertificate((PrivateKey) key, (X509Certificate) certificate);
  }

  @Override
  public String getShortName() {
    return "apk_builder";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format(
        // TODO(mbolin): Make the directory that corresponds to $ANDROID_HOME a field that is
        // accessible via an AndroidPlatformTarget and insert that here in place of "$ANDROID_HOME".
        "java -classpath $ANDROID_HOME/tools/lib/sdklib.jar %s %s -v -u %s -z %s %s -f %s",
        "com.android.sdklib.build.ApkBuilderMain",
        pathToOutputApkFile,
        Joiner.on(' ').join(Iterables.transform(nativeLibraryDirectories,
            new Function<Path, String>() {
              @Override
              public String apply(Path s) {
                return "-nf " + s;
              }
            })),
        resourceApk,
        Joiner.on(' ').join(Iterables.transform(assetDirectories,
            new Function<String, String>() {
              @Override
              public String apply(String s) {
                return "-rf " + s;
              }
            })),
        dexFile);
  }

  private static class PrivateKeyAndCertificate {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    PrivateKeyAndCertificate(PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = Preconditions.checkNotNull(privateKey);
      this.certificate = Preconditions.checkNotNull(certificate);
    }
  }
}
