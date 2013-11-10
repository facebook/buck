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
import com.google.common.base.Optional;
import com.intellij.openapi.diagnostic.Logger;

public class TestCommand {

  private static final Logger LOG = Logger.getInstance(TestCommand.class);

  private TestCommand() {}

  public static void runTest(BuckRunner buckRunner, Optional<BuckTarget> target) {
    String targetName = target.isPresent() ? target.get().getFullName() : "--all";
    int exitCode = buckRunner.executeAndListenToWebsocket("test", targetName);
    if (exitCode != 0) {
      // TODO Show stdout to a console tab
      LOG.error(buckRunner.getStderr());
    }
  }
}
