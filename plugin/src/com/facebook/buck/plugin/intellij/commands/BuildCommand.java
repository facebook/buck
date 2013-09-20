/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.plugin.intellij.commands;

import com.facebook.buck.plugin.intellij.BuckTarget;
import com.intellij.openapi.diagnostic.Logger;

public class BuildCommand {

  private static final Logger LOG = Logger.getInstance(BuildCommand.class);

  private BuildCommand() {}

  public static void build(BuckRunner buckRunner, BuckTarget target) {
    int exitCode = buckRunner.execute("build", target.getFullName());
    if (exitCode != 0) {
      LOG.error(buckRunner.getStderr());
      return;
    }
  }
}
