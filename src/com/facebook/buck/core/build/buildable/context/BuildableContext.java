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

package com.facebook.buck.core.build.buildable.context;

import java.nio.file.Path;

/**
 * Context object that is specific to an individual {@link com.facebook.buck.core.rules.BuildRule}.
 * This differs from {@link com.facebook.buck.core.build.context.BuildContext} in that a {@link
 * com.facebook.buck.core.build.context.BuildContext} is a context that is shared by all {@link
 * com.facebook.buck.core.rules.BuildRule}s whereas a new {@link BuildableContext} is created for
 * each call to {@link
 * com.facebook.buck.core.rules.BuildRule#getBuildSteps(com.facebook.buck.core.build.context.BuildContext,
 * BuildableContext)}.
 */
public interface BuildableContext {
  /** @see com.facebook.buck.core.build.engine.buildinfo.BuildInfoRecorder#recordArtifact(Path) */
  void recordArtifact(Path pathToArtifact);
}
