/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * This provides a base class for build rules that build/pack native libraries / shared objects.
 * All NativeLibraryRule's should produce a zip file containing the output of building the rule.
 *
 * See NdkLibraryRule and PrebuiltNativeLibraryBuildRule for concrete examples.
 */
abstract public class NativeLibraryRule extends ArchivingRule {
  private final boolean isAsset;
  private final String libraryPath;

  public NativeLibraryRule(BuildRuleParams params, boolean isAsset, String libraryPath) {
    super(params);
    this.isAsset = isAsset;
    this.libraryPath = libraryPath;
  }


  public boolean isAsset() {
    return isAsset;
  }

  @Override
  protected Path getArchivePath() {
    return Paths.get(getLibraryPath());
  }

  /**
   * The directory containing all shared objects built by this rule. This
   * value does *not* include a trailing slash.
   */
  public String getLibraryPath() {
    return libraryPath;
  }

  abstract public static class Builder<T extends NativeLibraryRule> extends AbstractBuildRuleBuilder<T> {

    protected boolean isAsset = false;

    protected Builder(AbstractBuildRuleBuilderParams params) {
      super(params);
    }

    public Builder<T> setIsAsset(boolean isAsset) {
      this.isAsset = isAsset;
      return this;
    }
  }
}
