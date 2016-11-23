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

import com.facebook.buck.util.DirtyPrintStreamDecorator;
import com.facebook.buck.util.cache.FileHashCacheVerificationResult;

import java.io.IOException;

/**
 * Verify the contents of our FileHashCache.
 */
public class VerifyHashCacheCommand extends AbstractCommand {
  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    FileHashCacheVerificationResult result = params.getFileHashCache().verify();
    DirtyPrintStreamDecorator stdOut = params.getConsole().getStdOut();
    stdOut.println("Examined " + result.getCachesExamined() + " caches.");
    stdOut.println("Examined " + result.getFilesExamined() + " files.");
    if (result.getVerificationErrors().isEmpty()) {
      stdOut.println("No errors");
      return 0;
    } else {
      stdOut.println("Errors detected:");
      for (String err : result.getVerificationErrors()) {
        stdOut.println("  " + err);
      }
      return 1;
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @Override
  public String getShortDescription() {
    return "Verify contents of FileHashCache";
  }
}
