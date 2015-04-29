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

package com.facebook.buck.java.intellij;

import com.facebook.buck.cli.BuckConfig;
import com.google.common.base.Optional;

import java.nio.file.Path;

public class IntellijConfig {

  private static final String sectionName = "intellij";

  private final BuckConfig delegate;

  public IntellijConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<String> getJdkName() {
    return delegate.getValue(sectionName, "jdk_name");
  }

  public Optional<String> getJdkType() {
    return delegate.getValue(sectionName, "jdk_type");
  }

  public Optional<String> getLanguageLevel() {
    return delegate.getValue(sectionName, "language_level");
  }

  public Optional<String> getOutputUrl() {
    return delegate.getValue(sectionName, "output_url");
  }

  public Optional<Path> getAndroidManifest() {
    return delegate.getPath(sectionName, "default_android_manifest_path", false);
  }

  public Optional<Path> getAndroidResources() {
    return delegate.getPath(sectionName, "default_android_resource_path", false);
  }

  public Optional<Path> getAndroidAssets() {
    return delegate.getPath(sectionName, "default_android_assets_path", false);
  }
}
