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

package com.facebook.buck.shell;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

class SignApkCommand extends ShellCommand {

  private final String outputPath;
  private final String unsignedApk;
  private final String keystore;
  private final String storepass;
  private final String keypass;
  private final String alias;

  public SignApkCommand(String outputPath, String unsignedApk, String keystore,
      String storepass, String keypass, String alias) {
    this.outputPath = outputPath;
    this.unsignedApk = unsignedApk;
    this.keystore = keystore;
    this.storepass = storepass;
    this.keypass = keypass;
    this.alias = alias;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> args = ImmutableList.builder();
    args.add("jarsigner");

    // As of Java 7, these arguments are necessary (http://stackoverflow.com/a/10083269/396304).
    args.add("-sigalg").add("MD5withRSA");
    args.add("-digestalg").add("SHA1");

    args.add("-keystore").add(keystore);
    args.add("-storepass").add(storepass);
    args.add("-keypass").add(keypass);
    args.add("-signedjar").add(outputPath);
    args.add(unsignedApk);
    args.add(alias);

    return args.build();
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "jarsigner";
  }

  @VisibleForTesting
  String getOutputPath() {
    return outputPath;
  }

  @VisibleForTesting
  String getUnsignedApk() {
    return unsignedApk;
  }

  @VisibleForTesting
  String getKeystore() {
    return keystore;
  }

  @VisibleForTesting
  String getStorepass() {
    return storepass;
  }

  @VisibleForTesting
  String getKeypass() {
    return keypass;
  }

  @VisibleForTesting
  String getAlias() {
    return alias;
  }

}
