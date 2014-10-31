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
import com.google.common.base.Optional;

public class IosPostprocessResourcesBuilder
    extends AbstractNodeBuilder<IosPostprocessResourcesDescription.Arg> {

  protected IosPostprocessResourcesBuilder(BuildTarget target) {
    super(new IosPostprocessResourcesDescription(), target);
  }

  public static IosPostprocessResourcesBuilder createBuilder(BuildTarget target) {
    return new IosPostprocessResourcesBuilder(target);
  }

  public IosPostprocessResourcesBuilder setCmd(Optional<String> cmd) {
    arg.cmd = cmd;
    return this;
  }

}
