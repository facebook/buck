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

package com.facebook.buck.apple;

import com.facebook.buck.apple.toolchain.AppleCxxPlatform;
import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.rules.impl.NoopBuildRule;
import com.facebook.buck.io.filesystem.ProjectFilesystem;

/**
 * This {@link BuildRule} provides {@link AppleCxxPlatform} to use in {@link
 * AppleToolchainSetBuildRule}. It's a {@link NoopBuildRule} with no build steps or outputs.
 */
public class AppleToolchainBuildRule extends NoopBuildRule {

  private final AppleCxxPlatform appleCxxPlatform;

  public AppleToolchainBuildRule(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      AppleCxxPlatform appleCxxPlatform) {
    super(buildTarget, projectFilesystem);

    this.appleCxxPlatform = appleCxxPlatform;
  }

  public AppleCxxPlatform getAppleCxxPlatform() {
    return appleCxxPlatform;
  }
}
