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

package com.facebook.buck.skylark.parser.context;

import com.facebook.buck.core.model.label.Label;
import com.facebook.buck.core.model.label.RepositoryName;
import com.google.common.collect.ImmutableMap;
import net.starlark.java.eval.StarlarkThread;

/** Dummy repo mapping for Buck. */
public class BuckHasRepoMapping implements Label.HasRepoMapping {

  private BuckHasRepoMapping() {}

  @Override
  public ImmutableMap<RepositoryName, RepositoryName> getRepoMapping() {
    // TODO(nga): put something here to resolve labels properly
    return ImmutableMap.of();
  }

  public static final BuckHasRepoMapping HAS_REPO_MAPPING = new BuckHasRepoMapping();

  /** Register repo mapping in Starlark. */
  public void setup(StarlarkThread thread) {
    thread.setThreadLocal(Label.HasRepoMapping.class, this);
  }
}
