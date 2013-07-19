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

import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.KeystoreProperties;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import java.io.IOException;

class SignApkStep extends ShellStep {

  private final String pathToKeystore;
  private final String pathToPropertiesFile;
  private final String outputPath;
  private final String unsignedApk;

  public SignApkStep(String pathToKeystore,
      String pathToPropertiesFile,
      String unsignedApk,
      String outputPath) {
    this.pathToKeystore = Preconditions.checkNotNull(pathToKeystore);
    this.pathToPropertiesFile = Preconditions.checkNotNull(pathToPropertiesFile);
    this.outputPath = Preconditions.checkNotNull(outputPath);
    this.unsignedApk = Preconditions.checkNotNull(unsignedApk);
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    // Read the values out of the properties file to pass to jarsigner.
    KeystoreProperties properties;
    try {
      properties = KeystoreProperties.createFromPropertiesFile(
          pathToKeystore, pathToPropertiesFile, context.getProjectFilesystem());
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("jarsigner");

    // As of Java 7, these arguments are necessary (http://stackoverflow.com/a/10083269/396304).
    args.add("-sigalg").add("MD5withRSA");
    args.add("-digestalg").add("SHA1");

    args.add("-keystore").add(pathToKeystore);
    args.add("-storepass").add(properties.getStorepass());
    args.add("-keypass").add(properties.getKeypass());
    args.add("-signedjar").add(outputPath);
    args.add(unsignedApk);
    args.add(properties.getAlias());

    return args.build();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "jarsigner";
  }

}
