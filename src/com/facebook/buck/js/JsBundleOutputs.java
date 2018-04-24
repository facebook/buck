/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.js;

import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.BuildRule;

/**
 * Represents output paths of JS builds, consisting of JavaScript build output, a corresponding
 * source map, "misc" directory that can contain diverse assets not meant to be part of the app
 * being shipped and assets/resources used from within the packaged JS source code.
 */
public interface JsBundleOutputs extends BuildRule {
  String JS_DIR_NAME = "js";

  /**
   * @return the file name of the main JavaScript bundle file. This does not necessarily have to be
   *     the only JavaScript file written.
   */
  String getBundleName();

  /** @return the {@link SourcePath} to the directory containing the built JavaScript. */
  @Override
  default SourcePath getSourcePathToOutput() {
    return JsUtil.relativeToOutputRoot(getBuildTarget(), getProjectFilesystem(), JS_DIR_NAME);
  }

  /**
   * @return a {@link SourcePath} to a source map belonging to the built JavaScript. Typically a
   *     single file.
   */
  default SourcePath getSourcePathToSourceMap() {
    return JsUtil.relativeToOutputRoot(
        getBuildTarget(), getProjectFilesystem(), JsUtil.getSourcemapPath(this));
  }

  /**
   * @return the {@link SourcePath} to a directory containing the resources (or assets) used by the
   *     bundled JavaScript source code.
   */
  default SourcePath getSourcePathToResources() {
    return JsUtil.relativeToOutputRoot(getBuildTarget(), getProjectFilesystem(), "res");
  }

  /**
   * @return the {@link SourcePath} to a directory containing various metadata that can be used by
   *     dependent rules but are not meant to be shipped with the application.
   */
  default SourcePath getSourcePathToMisc() {
    return JsUtil.relativeToOutputRoot(getBuildTarget(), getProjectFilesystem(), "misc");
  }
}
