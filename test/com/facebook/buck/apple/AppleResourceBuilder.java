/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import java.util.Set;

public class AppleResourceBuilder
    extends AbstractNodeBuilder<
        AppleResourceDescriptionArg.Builder,
        AppleResourceDescriptionArg,
        AppleResourceDescription,
        BuildRule> {

  protected AppleResourceBuilder(BuildTarget target) {
    super(new AppleResourceDescription(), target);
  }

  public static AppleResourceBuilder createBuilder(BuildTarget target) {
    return new AppleResourceBuilder(target);
  }

  public AppleResourceBuilder setDirs(Set<SourcePath> dirs) {
    getArgForPopulating().setDirs(dirs);
    return this;
  }

  public AppleResourceBuilder setFiles(Set<SourcePath> files) {
    getArgForPopulating().setFiles(files);
    return this;
  }

  public AppleResourceBuilder setVariants(Set<SourcePath> variants) {
    getArgForPopulating().setVariants(variants);
    return this;
  }
}
