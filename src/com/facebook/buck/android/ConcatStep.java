/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.android;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Takes in a list of files and outputs a
 * concatenation of them in the same directory. Used for solid compression
 * into a single .xz. Does NOT delete source files after concatenating.
 */
public class ConcatStep implements Step {

  public static final String SHORT_NAME = "concat";
  private final List<Path> dexes;
  private final Path output;

  /**
   * @param inputs The .dex files to be concatenated
   * @param outputPath The desired output path.
   */
  public ConcatStep(List<Path> inputs, Path outputPath) {
    dexes = new ArrayList<>();
    for (Path p : inputs) {
      dexes.add(p);
    }
    output = outputPath;
  }

  @Override
  public int execute(ExecutionContext context) {
    try (
        OutputStream out = new BufferedOutputStream(Files.newOutputStream(output));
    ) {
      for (Path p : dexes) {
        Files.copy(p, out);
        out.flush();
      }
    } catch (IOException e) {
      context.logError(e, "There was an error in concat step");
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  public String getDescription(ExecutionContext context) {
    StringBuilder desc = new StringBuilder();
    desc.append(getShortName());
    desc.append(" inputs: ");
    for (Path p : dexes) {
      desc.append(p).append(' ');
    }
    desc.append("output: ");
    desc.append(output);
    return desc.toString();

  }
}
