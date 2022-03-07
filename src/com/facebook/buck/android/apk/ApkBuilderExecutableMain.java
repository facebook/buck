/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link ApkBuilderUtils} calls. */
public class ApkBuilderExecutableMain {
  @Option(name = "--output-apk", required = true)
  private String outputApk;

  @Option(name = "--resource-apk", required = true)
  private String resourceApk;

  @Option(name = "--dex-file", required = true)
  private String dexFile;

  @Option(name = "--keystore-path", required = true)
  private String keystore;

  @Option(name = "--keystore-properties-path", required = true)
  private String keystoreProperties;

  @Option(name = "--asset-directories-list", required = true)
  private String assetDirectoriesList;

  @Option(name = "--native-libraries-directories-list", required = true)
  private String nativeLibrariesDirectoriesList;

  @Option(name = "--zip-files-list", required = true)
  private String zipFilesList;

  @Option(name = "--jar-files-that-may-contain-resources-list", required = true)
  private String jarFilesThatMayContainResourcesList;

  public static void main(String[] args) throws IOException {
    ApkBuilderExecutableMain main = new ApkBuilderExecutableMain();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      main.run();
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private void run() throws IOException {
    ImmutableSet<Path> assetDirectories =
        Files.readAllLines(Paths.get(assetDirectoriesList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<Path> nativeLibraryDirectories =
        Files.readAllLines(Paths.get(nativeLibrariesDirectoriesList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<Path> zipFiles =
        Files.readAllLines(Paths.get(zipFilesList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    ImmutableSet<Path> jarFilesThatMayContainResources =
        Files.readAllLines(Paths.get(jarFilesThatMayContainResourcesList)).stream()
            .map(Paths::get)
            .collect(ImmutableSet.toImmutableSet());

    Path keystorePath = Paths.get(keystore);
    Path keystorePropertiesPath = Paths.get(keystoreProperties);

    try {
      ApkBuilderUtils.buildApk(
          Paths.get(resourceApk),
          Paths.get(outputApk),
          Paths.get(dexFile),
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
