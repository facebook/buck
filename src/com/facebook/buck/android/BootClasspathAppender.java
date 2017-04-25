/*
 * Copyright 2015-present Facebook, Inc.
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

import com.facebook.buck.jvm.java.JavacOptions;
import com.facebook.buck.jvm.java.JavacOptionsAmender;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

class BootClasspathAppender implements JavacOptionsAmender {

  private static String androidBootclasspath(AndroidPlatformTarget platform) {
    List<Path> bootclasspathEntries = platform.getBootclasspathEntries();
    Preconditions.checkState(
        !bootclasspathEntries.isEmpty(), "There should be entries for the bootclasspath");
    return Joiner.on(File.pathSeparator).join(bootclasspathEntries);
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    sink.setReflectively("bootclasspath", "android");
  }

  @Override
  public JavacOptions amend(JavacOptions original, BuildContext context) {
    return JavacOptions.builder(original)
        .setBootclasspath(androidBootclasspath(context.getAndroidPlatformTargetSupplier().get()))
        .build();
  }
}
