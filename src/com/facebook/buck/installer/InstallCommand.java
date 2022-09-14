/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.installer;

import java.nio.file.Path;

/**
 * Install Command interface. Support for a specific installer such as iOS or Android is implemented
 * as subclasses of this interface. *
 */
public interface InstallCommand {

  /** Installs an artifact from a given path/location. */
  InstallResult fileReady(String artifact, Path artifactPath, InstallId installId);

  /** Indicate that all files have been received by the installer */
  InstallResult allFilesReady(InstallId installId);
}
