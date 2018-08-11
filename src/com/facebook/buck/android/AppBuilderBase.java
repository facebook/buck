/*
 * Copyright 2018-present Facebook, Inc.
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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.io.IOException;
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
import java.util.function.Supplier;

/**
 * A class that provides information that are shared across AabBuilder and ApkBuilder, currently
 * only private key and certificate are needed
 */
public class AppBuilderBase {

  /**
   * The type of a keystore created via the {@code jarsigner} command in Sun/Oracle Java. See
   * http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#KeyStore.
   */
  private static final String JARSIGNER_KEY_STORE_TYPE = "jks";

  private final ProjectFilesystem filesystem;
  private final Supplier<KeystoreProperties> keystorePropertiesSupplier;
  private final Path pathToKeystore;

  public AppBuilderBase(
      ProjectFilesystem filesystem,
      Supplier<KeystoreProperties> keystorePropertiesSupplier,
      Path pathToKeystore) {
    this.filesystem = filesystem;
    this.keystorePropertiesSupplier = keystorePropertiesSupplier;
    this.pathToKeystore = pathToKeystore;
  }

  protected PrivateKeyAndCertificate createKeystoreProperties()
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

  protected static class PrivateKeyAndCertificate {
    protected final PrivateKey privateKey;
    protected final X509Certificate certificate;

    PrivateKeyAndCertificate(PrivateKey privateKey, X509Certificate certificate) {
      this.privateKey = privateKey;
      this.certificate = certificate;
    }
  }
}
