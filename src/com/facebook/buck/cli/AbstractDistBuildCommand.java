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

import com.facebook.buck.distributed.thrift.StampedeId;
import com.facebook.buck.util.HumanReadableException;
import java.util.Optional;
import javax.annotation.Nullable;
import org.kohsuke.args4j.Option;

public abstract class AbstractDistBuildCommand extends AbstractCommand {

  protected static final String STAMPEDE_ID_ARG_NAME = "--stampede-id";

  @Nullable
  @Option(name = STAMPEDE_ID_ARG_NAME, usage = "Stampede distributed build id.")
  private String stampedeId;

  public Optional<StampedeId> getStampedeIdOptional() {
    if (stampedeId == null) {
      return Optional.empty();
    }

    StampedeId stampedeId = new StampedeId();
    stampedeId.setId(this.stampedeId);
    return Optional.of(stampedeId);
  }

  public StampedeId getStampedeId() {
    Optional<StampedeId> buildId = getStampedeIdOptional();
    if (!buildId.isPresent()) {
      throw new HumanReadableException(
          String.format("%s argument is missing.", STAMPEDE_ID_ARG_NAME));
    }

    return buildId.get();
  }
}
