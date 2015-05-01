/*
 * Copyright 2012-present Facebook, Inc.
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

import org.kohsuke.args4j.Option;

public class AuditOwnerOptions extends AuditCommandOptions {

  @Option(name = "--full",
      aliases = { "-f" },
      usage = "Full report with all details about what targets own what files.")
  private boolean fullReport;

  @Option(name = "--guess-for-missing",
      aliases = { "-g" },
      usage = "Guess targets for deleted files by including all rules from guessed BUCK files.")
  private boolean guessForDeleted;

  public boolean isFullReportEnabled() {
    return fullReport;
  }

  public boolean isGuessForDeletedEnabled() {
    return guessForDeleted;
  }
}
