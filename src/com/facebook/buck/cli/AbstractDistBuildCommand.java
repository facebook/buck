/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.android.common.annotations.Nullable;
import com.facebook.buck.distributed.thrift.BuildId;
import com.facebook.buck.util.HumanReadableException;

import org.kohsuke.args4j.Option;

public abstract class AbstractDistBuildCommand extends AbstractCommand {

  @Nullable
  @Option(name = "--build-id", usage = "Distributed build id.")
  private String buildId;

  public BuildId getBuildId() {
    if (buildId == null) {
      throw new HumanReadableException("--build-id argument is missing.");
    }
    BuildId buildId = new BuildId();
    buildId.setId(this.buildId);
    return buildId;
  }
}
