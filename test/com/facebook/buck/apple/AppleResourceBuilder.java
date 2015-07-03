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

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Optional;

import java.util.Set;

public class AppleResourceBuilder extends AbstractNodeBuilder<AppleResourceDescription.Arg> {

  protected AppleResourceBuilder(BuildTarget target) {
    super(new AppleResourceDescription(), target);
  }

  public static AppleResourceBuilder createBuilder(BuildTarget target) {
    return new AppleResourceBuilder(target);
  }

  public AppleResourceBuilder setDirs(Set<SourcePath> dirs) {
    arg.dirs = dirs;
    return this;
  }

  public AppleResourceBuilder setFiles(Set<SourcePath> files) {
    arg.files = files;
    return this;
  }

  public AppleResourceBuilder setVariants(Optional<Set<SourcePath>> variants) {
    arg.variants = variants;
    return this;
  }

}
