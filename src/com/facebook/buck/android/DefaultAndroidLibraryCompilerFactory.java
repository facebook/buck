/*
 * Copyright 2016-present Facebook, Inc.
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

import com.facebook.buck.jvm.kotlin.KotlinBuckConfig;
import com.facebook.buck.jvm.scala.ScalaBuckConfig;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;

import com.google.common.io.Files;

public class DefaultAndroidLibraryCompilerFactory implements AndroidLibraryCompilerFactory {

  private final ScalaBuckConfig scalaConfig;
  private final KotlinBuckConfig kotlinBuckConfig;

  public DefaultAndroidLibraryCompilerFactory(
      ScalaBuckConfig scalaConfig, KotlinBuckConfig kotlinBuckConfig) {
    this.scalaConfig = scalaConfig;
    this.kotlinBuckConfig = kotlinBuckConfig;
  }

  @Override
  public AndroidLibraryCompiler getCompiler(AndroidLibraryDescription.Arg args) {
    // We currently expect all non-java languages to only contain files of that extension.
    for (SourcePath sourcePath : args.srcs.get()) {
      if (sourcePath instanceof PathSourcePath) {
        String extension = Files.getFileExtension(sourcePath.toString()).toLowerCase();
        if (extension.contains(KotlinBuckConfig.EXTENSION)) {
          return new KotlinAndroidLibraryCompiler(kotlinBuckConfig);
        } else if (extension.contains(ScalaBuckConfig.EXTENSION)) {
          return new ScalaAndroidLibraryCompiler(scalaConfig);
        }
      }
    }

    return new JavaAndroidLibraryCompiler();
  }

}
