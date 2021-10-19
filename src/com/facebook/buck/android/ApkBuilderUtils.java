/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.android;

import com.android.common.sdklib.build.ApkBuilder;
import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.facebook.buck.core.exceptions.HumanReadableException;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
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

/** A class that provides methods useful for building an APK. */
public class ApkBuilderUtils {

  /**
   * The type of a keystore created via the {@code jarsigner} command in Sun/Oracle Java. See
   * http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore.
   */
  private static final String JARSIGNER_KEY_STORE_TYPE = "jks";

  public static void buildApk(
      Path resourceApk,
      Path pathToOutputApkFile,
      Path dexFile,
      ImmutableSet<Path> assetDirectories,
      ImmutableSet<Path> nativeLibraryDirectories,
      ImmutableSet<Path> zipFiles,
      ImmutableSet<Path> jarFilesThatMayContainResources,
      Path pathToKeystore,
      KeystoreProperties keystoreProperties,
      PrintStream output)
      throws IOException, ApkCreationException, KeyStoreException, NoSuchAlgorithmException,
          SealedApkException, UnrecoverableKeyException, DuplicateFileException {
    PrivateKeyAndCertificate privateKeyAndCertificate =
        getPrivateKeyAndCertificate(keystoreProperties, pathToKeystore);
    ApkBuilder builder =
        new ApkBuilder(
            pathToOutputApkFile.toFile(),
            resourceApk.toFile(),
            dexFile.toFile(),
            privateKeyAndCertificate.privateKey,
            privateKeyAndCertificate.certificate,
            output);
    for (Path nativeLibraryDirectory : nativeLibraryDirectories) {
      builder.addNativeLibraries(nativeLibraryDirectory.toFile());
    }
    for (Path assetDirectory : assetDirectories) {
      builder.addSourceFolder(assetDirectory.toFile());
    }
    for (Path zipFile : zipFiles) {
      // TODO(natthu): Skipping silently is bad. These should really be assertions.
      if (Files.exists(zipFile) && Files.isRegularFile(zipFile)) {
        builder.addZipFile(zipFile.toFile());
      }
    }
    for (Path jarFileThatMayContainResources : jarFilesThatMayContainResources) {
      builder.addResourcesFromJar(jarFileThatMayContainResources.toFile());
    }

    // Build the APK
    builder.sealApk();
  }

  private static PrivateKeyAndCertificate getPrivateKeyAndCertificate(
      KeystoreProperties keystoreProperties, Path pathToKeystore)
      throws IOException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
    KeyStore keystore = KeyStore.getInstance(JARSIGNER_KEY_STORE_TYPE);
    char[] keystorePassword = keystoreProperties.getStorepass().toCharArray();
    try {
      keystore.load(Files.newInputStream(pathToKeystore), keystorePassword);
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

  private static class PrivateKeyAndCertificate {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    PrivateKeyAndCertificate(PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = privateKey;
      this.certificate = certificate;
    }
  }
}
