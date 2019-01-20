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

import com.android.apksig.ApkSigner;
import com.android.sdklib.build.ApkCreationException;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.StepExecutionResult;
import com.facebook.buck.step.StepExecutionResults;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Use Google apksigner to v1/v2/v3 sign the final APK */
class ApkSignerStep implements Step {

  private final ProjectFilesystem filesystem;
  private final Path inputApkPath;
  private final Path outputApkPath;
  private final Supplier<KeystoreProperties> keystorePropertiesSupplier;
  private final boolean isRedexBuild;

  public ApkSignerStep(
      ProjectFilesystem filesystem,
      Path inputApkPath,
      Path outputApkPath,
      Supplier<KeystoreProperties> keystorePropertiesSupplier,
      boolean isRedexBuild) {
    this.filesystem = filesystem;
    this.inputApkPath = inputApkPath;
    this.outputApkPath = outputApkPath;
    this.keystorePropertiesSupplier = keystorePropertiesSupplier;
    this.isRedexBuild = isRedexBuild;
  }

  @Override
  public StepExecutionResult execute(ExecutionContext context) {
    try {
      ImmutableList<ApkSigner.SignerConfig> signerConfigs = getSignerConfigs();
      File inputApkFile = filesystem.getPathForRelativePath(inputApkPath).toFile();
      File outputApkFile = filesystem.getPathForRelativePath(outputApkPath).toFile();
      signApkFile(inputApkFile, outputApkFile, signerConfigs);
    } catch (Exception e) {
      context.logError(e, "Error when signing APK at: %s.", outputApkPath);
      return StepExecutionResults.ERROR;
    }
    return StepExecutionResults.SUCCESS;
  }

  /** Sign the APK using Google's {@link com.android.apksig.ApkSigner} */
  private void signApkFile(
      File inputApk, File outputApk, ImmutableList<ApkSigner.SignerConfig> signerConfigs)
      throws ApkCreationException {
    ApkSigner.Builder apkSignerBuilder = new ApkSigner.Builder(signerConfigs);
    // For non-redex build, apkSignerBuilder can look up minimum SDK version from
    // AndroidManifest.xml. Redex build does not have AndroidManifest.xml, so we
    // manually set it here.
    if (isRedexBuild) {
      apkSignerBuilder.setMinSdkVersion(1);
    }
    try {
      apkSignerBuilder
          .setV1SigningEnabled(true)
          .setV2SigningEnabled(true)
          .setV3SigningEnabled(false)
          .setInputApk(inputApk)
          .setOutputApk(outputApk)
          .build()
          .sign();
    } catch (Exception e) {
      throw new ApkCreationException(e, "Failed to sign APK");
    }
  }

  private ImmutableList<ApkSigner.SignerConfig> getSignerConfigs() throws KeyStoreException {
    KeystoreProperties keystoreProperties = keystorePropertiesSupplier.get();
    Path keystorePath = keystoreProperties.getKeystore();
    char[] keystorePassword = keystoreProperties.getStorepass().toCharArray();
    String keyAlias = keystoreProperties.getAlias();
    char[] keyPassword = keystoreProperties.getKeypass().toCharArray();
    KeyStore keystore = loadKeyStore(keystorePath, keystorePassword);
    PrivateKey key = loadPrivateKey(keystore, keyAlias, keyPassword);
    List<X509Certificate> certs = loadCertificates(keystore, keyAlias);
    ApkSigner.SignerConfig signerConfig =
        new ApkSigner.SignerConfig.Builder("CERT", key, certs).build();
    return ImmutableList.of(signerConfig);
  }

  private KeyStore loadKeyStore(Path keystorePath, char[] keystorePassword)
      throws KeyStoreException {
    try {
      String ksType = KeyStore.getDefaultType();
      KeyStore keystore = KeyStore.getInstance(ksType);
      keystore.load(filesystem.getInputStreamForRelativePath(keystorePath), keystorePassword);
      return keystore;
    } catch (Exception e) {
      throw new KeyStoreException("Failed to load keystore from " + keystorePath);
    }
  }

  private PrivateKey loadPrivateKey(KeyStore keystore, String keyAlias, char[] keyPassword)
      throws KeyStoreException {
    PrivateKey key;
    try {
      key = (PrivateKey) keystore.getKey(keyAlias, keyPassword);
      // key can be null if alias/password is incorrect.
      Objects.requireNonNull(key);
    } catch (Exception e) {
      throw new KeyStoreException(
          "Failed to load private key \"" + keyAlias + "\" from " + keystore);
    }
    return key;
  }

  private List<X509Certificate> loadCertificates(KeyStore keystore, String keyAlias)
      throws KeyStoreException {
    Certificate[] certChain = keystore.getCertificateChain(keyAlias);
    if ((certChain == null) || (certChain.length == 0)) {
      throw new KeyStoreException(
          keystore + " entry \"" + keyAlias + "\" does not contain certificates");
    }
    List<X509Certificate> certs = new ArrayList<>(certChain.length);
    for (Certificate cert : certChain) {
      certs.add((X509Certificate) cert);
    }
    return certs;
  }

  @Override
  public String getShortName() {
    return "apk_signer";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return getShortName();
  }
}
