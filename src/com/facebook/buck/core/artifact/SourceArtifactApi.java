/*
 * Copyright 2019-present Facebook, Inc.
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
package com.facebook.buck.core.artifact;

import com.facebook.buck.core.sourcepath.PathSourcePath;

/**
 * Represents an artifact that is a source file in the project
 *
 * <p>This interface is not intended to be used by the user, but by the framework
 */
public interface SourceArtifactApi extends Artifact {

  /** @return the path to the source file */
  PathSourcePath getSourcePath();
}
