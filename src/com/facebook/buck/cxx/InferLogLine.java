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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.CellPathResolver;
import com.google.common.base.Optional;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class InferLogLine {
  private static final String SPLIT_TOKEN = "\t";
  private static final String CELL_TOKEN_SUFFIX = "//";

  private final String target;
  private final String flavors;
  private final String output;

  private InferLogLine(String target, String flavors, String output) {
    this.target = target;
    this.flavors = flavors;
    this.output = output;
  }

  public InferLogLine(BuildTarget target, Path output) {
    this(
        target.toString(),
        target.getFlavors().toString(),
        convertCellNameToCellToken(target.getCell()) + output.toString());
  }

  public static InferLogLine fromLine(String line) {
    String[] el = line.split(SPLIT_TOKEN);
    return new InferLogLine(el[0], el[1], el[2]);
  }

  private static String buildOutput(String target, String flavors, String output) {
    return target + SPLIT_TOKEN + flavors + SPLIT_TOKEN + output;
  }

  @Override
  public String toString() {
    return buildOutput(target, flavors, output);
  }

  public String toContextualizedString(CellPathResolver cellPathResolver) throws IOException {
    return buildOutput(
        target, flavors, computeContextualizedOutputPath(cellPathResolver).toString());
  }

  private Path computeContextualizedOutputPath(
      CellPathResolver cellPathResolver) throws IOException {
    int tokenPos = output.indexOf(CELL_TOKEN_SUFFIX);
    if (tokenPos < 0) {
      return cellPathResolver.getCellPath(Optional.<String>absent()).resolve(Paths.get(output));
    } else if (tokenPos == 0) {
      throw new IOException("malformed input");
    } else {
      String cellName = output.substring(0, tokenPos);
      String outputWithoutCellToken = output.substring(tokenPos + CELL_TOKEN_SUFFIX.length());
      Path outputPath = Paths.get(outputWithoutCellToken);
      Path cellPath = cellPathResolver.getCellPath(Optional.of(cellName));
      return cellPath.resolve(outputPath);
    }
  }

  private static String convertCellNameToCellToken(Optional<String> cellName) {
    return cellName.isPresent() ? cellName.get() + CELL_TOKEN_SUFFIX : "";
  }
}
