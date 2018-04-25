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

package com.facebook.buck.android;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.RichStream;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * HasInstallableApk is, sadly, cannot depend on BuildRule. This class is just used for things that
 * would go in HasInstallableApk but cannot due to that restriction.
 */
class HasInstallableApkSupport {
  /**
   * Computes the runtime deps required to install this apk from the ApkInfo (excluding this rule
   * itself).
   */
  static Stream<BuildTarget> getRuntimeDepsForInstallableApk(
      HasInstallableApk hasInstallableApk, SourcePathRuleFinder ruleFinder) {
    Stream<SourcePath> requiredPaths =
        RichStream.of(
                hasInstallableApk.getApkInfo().getManifestPath(),
                hasInstallableApk.getApkInfo().getApkPath())
            .concat(
                hasInstallableApk
                    .getApkInfo()
                    .getExopackageInfo()
                    .map(info -> info.getRequiredPaths())
                    .orElse(RichStream.of()));
    return requiredPaths
        .map(ruleFinder::getRule)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(BuildRule::getBuildTarget)
        .filter(target -> target != hasInstallableApk.getBuildTarget());
  }
}
