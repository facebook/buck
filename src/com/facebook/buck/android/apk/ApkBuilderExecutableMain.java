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

package com.facebook.buck.android.apk;

import com.android.sdklib.build.ApkCreationException;
import com.android.sdklib.build.DuplicateFileException;
import com.android.sdklib.build.SealedApkException;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.logging.Logger; // NOPMD

/** Main entry point for executing {@link ApkBuilderUtils} calls. */
public class ApkBuilderExecutableMain {

  private static final Logger LOG = Logger.getLogger(ApkBuilderExecutableMain.class.getName());

  public static void main(String[] args) throws IOException {
    if (args.length != 9) {
      LOG.severe(
          "Must specify the following mandatory arguments:\n"
              + "output_apk resource_apk dex_file keystore_path keystore_properties_path "
              + "asset_directories native_library_directories zip_files jar_files_that_may_contain_resources\n");
      System.exit(1);
    }

    Path pathToOutputApkFile = Paths.get(args[0]);
    Path resourceApk = Paths.get(args[1]);
    Path dexFile = Paths.get(args[2]);
    Path keystorePath = Paths.get(args[3]);
    Path keystorePropertiesPath = Paths.get(args[4]);

    ImmutableSet<Path> assetDirectories =
        Files.readAllLines(Paths.get(args[5])).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<Path> nativeLibraryDirectories =
        Files.readAllLines(Paths.get(args[6])).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<Path> zipFiles =
        Files.readAllLines(Paths.get(args[7])).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<Path> jarFilesThatMayContainResources =
        Files.readAllLines(Paths.get(args[8])).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    try {
      ApkBuilderUtils.buildApk(
          resourceApk,
          pathToOutputApkFile,
          dexFile,
          assetDirectories,
          nativeLibraryDirectories,
          zipFiles,
          jarFilesThatMayContainResources,
          keystorePath,
          KeystoreProperties.createFromPropertiesFile(keystorePath, keystorePropertiesPath),
          null);
    } catch (UnrecoverableKeyException
        | NoSuchAlgorithmException
        | ApkCreationException
        | SealedApkException
        | KeyStoreException
        | DuplicateFileException e) {
      throw new RuntimeException(e);
    }

    System.exit(0);
  }
}
