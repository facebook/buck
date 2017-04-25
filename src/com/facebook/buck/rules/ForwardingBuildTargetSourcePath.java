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
import com.facebook.buck.model.Pair;

/** A {@link BuildTargetSourcePath} which resolves to the value of another SourcePath. */
public class ForwardingBuildTargetSourcePath
    extends BuildTargetSourcePath<ForwardingBuildTargetSourcePath> {
  private final SourcePath delegate;

  public ForwardingBuildTargetSourcePath(BuildTarget target, SourcePath delegate) {
    super(target);
    this.delegate = delegate;
  }

  public SourcePath getDelegate() {
    return delegate;
  }

  @Override
  protected Object asReference() {
    return new Pair<>(getTarget(), delegate);
  }

  @Override
  protected int compareReferences(ForwardingBuildTargetSourcePath o) {
    if (o == this) {
      return 0;
    }

    int res = getTarget().compareTo(o.getTarget());
    if (res != 0) {
      return res;
    }

    return delegate.compareTo(o.delegate);
  }
}
