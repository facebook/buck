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

package com.facebook.buck.worker;

import com.facebook.buck.util.immutables.BuckStyleTuple;
import java.nio.file.Path;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractWorkerProcessCommand {
  /**
   * Path to file which contains the arguments of the command. This content should be considered as
   * an input for the command.
   */
  public abstract Path getArgsPath();

  /**
   * Path to file where stdout can be written out. Remote process should output everything into this
   * file instead of printing out into its own stdout.
   */
  public abstract Path getStdOutPath();

  /**
   * Path to file where stderr can be written out. Remote process should output everything into this
   * file instead of printing out into its own stderr.
   */
  public abstract Path getStdErrPath();
}
