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

package com.facebook.buck.eden.cli;

import com.facebook.buck.eden.EdenClientPool;
import com.facebook.eden.thrift.EdenError;
import com.facebook.infer.annotation.SuppressFieldNotInitialized;
import com.facebook.thrift.TException;
import java.io.IOException;
import java.util.Objects;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.spi.SubCommand;
import org.kohsuke.args4j.spi.SubCommandHandler;
import org.kohsuke.args4j.spi.SubCommands;

public class Args {
  @Argument(required = true, index = 0, handler = SubCommandHandler.class)
  @SubCommands({
    @SubCommand(name = "mounts", impl = MountsCommand.class),
    @SubCommand(name = "sha1", impl = Sha1Command.class),
  })
  @SuppressFieldNotInitialized
  Command command;

  public int run(EdenClientPool pool) throws EdenError, IOException, TException {
    Objects.requireNonNull(command, "command must be set by args4j");
    return command.run(pool);
  }
}
