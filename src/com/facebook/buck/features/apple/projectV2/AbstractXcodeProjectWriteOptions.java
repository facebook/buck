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

package com.facebook.buck.features.apple.projectV2;

import com.facebook.buck.apple.xcode.xcodeproj.PBXProject;
import com.facebook.buck.core.util.immutables.BuckStyleImmutable;
import java.nio.file.Path;
import org.immutables.value.Value;

/** Options for writing to an Xcode project */
@Value.Immutable
@BuckStyleImmutable
abstract class AbstractXcodeProjectWriteOptions {
  /** The project being written to. */
  @Value.Parameter
  public abstract PBXProject project();

  /**
   * The directory containing the xcodeproj; this would be the parent directory.
   *
   * <p>e.g. if this is "/home/me/projects/MyApp.xcodepoj, then this would be "/home/me/projects"
   */
  @Value.Parameter
  public abstract Path sourceRoot();

  /** The absolute path to the .xcodeproj */
  @Value.Lazy
  public Path xcodeProjPath() {
    return this.sourceRoot().resolve(this.project().getName() + ".xcodeproj");
  }

  @Value.Lazy
  public Path projectFilePath() {
    return this.xcodeProjPath().resolve("project.pbxproj");
  }
}
