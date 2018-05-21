/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.features.filegroup;

import com.facebook.buck.core.model.BuildTarget;
import com.facebook.buck.core.model.targetgraph.AbstractNodeBuilder;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.features.filegroup.FileGroupDescriptionArg.Builder;
import com.google.common.collect.ImmutableSortedSet;

public class FilegroupBuilder
    extends AbstractNodeBuilder<Builder, FileGroupDescriptionArg, FilegroupDescription, Filegroup> {

  private FilegroupBuilder(BuildTarget target) {
    super(new FilegroupDescription(), target);
  }

  public static FilegroupBuilder createBuilder(BuildTarget target) {
    return new FilegroupBuilder(target);
  }

  public FilegroupBuilder setSrcs(ImmutableSortedSet<SourcePath> srcs) {
    getArgForPopulating().setSrcs(srcs);
    return this;
  }
}
