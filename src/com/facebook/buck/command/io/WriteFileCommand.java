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

package com.facebook.buck.command.io;

import com.facebook.buck.shell.Command;
import com.facebook.buck.shell.ExecutionContext;
import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import java.io.File;
import java.io.IOException;

public class WriteFileCommand implements Command {

  private final String content;
  private final String outputPath;

  public WriteFileCommand(String content, String outputPath) {
    this.content = Preconditions.checkNotNull(content);
    this.outputPath = Preconditions.checkNotNull(outputPath);
  }

  @Override
  public int execute(ExecutionContext context) {
    try {
      // echo by default writes a trailing new line and so should we.
      Files.write(content + "\n", new File(outputPath), Charsets.UTF_8);
      return 0;
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return 1;
    }
  }

  @Override
  public String getShortName(ExecutionContext context) {
    return "echo CONTENT > FILE";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("echo %s > %s", content, outputPath);
  }

}
