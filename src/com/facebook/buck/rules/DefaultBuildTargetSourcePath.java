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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;

/**
 * A {@link BuildTargetSourcePath} which resolves to the default output of the {@link BuildRule}
 * referred to by its target.
 */
public class DefaultBuildTargetSourcePath
    extends BuildTargetSourcePath<DefaultBuildTargetSourcePath> {

  public DefaultBuildTargetSourcePath(BuildTarget target) {
    super(target);
  }

  @Override
  protected Object asReference() {
    return getTarget();
  }

  @Override
  protected int compareReferences(DefaultBuildTargetSourcePath o) {
    return getTarget().compareTo(o.getTarget());
  }
}
