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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.google.common.base.Joiner;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

/**
 * Takes in a list of files and outputs a
 * concatenation of them in the same directory. Used for solid compression
 * into a single .xz. Does NOT delete source files after concatenating.
 */
public class ConcatStep implements Step {

  public static final String SHORT_NAME = "cat";

  private final ProjectFilesystem filesystem;
  private final Supplier<ImmutableList<Path>> inputs;
  private final Path output;

  /**
   * Use this constructor if the files to concatenate are known at the time of step creation.
   * @param inputs The files to be concatenated
   * @param outputPath The desired output path.
   */
  public ConcatStep(ProjectFilesystem filesystem, ImmutableList<Path> inputs, Path outputPath) {
    this.filesystem = filesystem;
    this.inputs = Suppliers.ofInstance(inputs);
    output = outputPath;
  }

  /**
   * Use this constructor if the files to concatenate are not known at the time of step creation.
   * @param inputsBuilder The files to be concatenated, in builder form
   * @param outputPath The desired output path.
   */
  public ConcatStep(
      ProjectFilesystem filesystem,
      final ImmutableList.Builder<Path> inputsBuilder,
      Path outputPath) {
    this.filesystem = filesystem;
    this.inputs = Suppliers.memoize(new Supplier<ImmutableList<Path>>() {
                                      @Override
                                      public ImmutableList<Path> get() {
                                        return inputsBuilder.build();
                                      }
                                    });
    output = outputPath;
  }

  @Override
  public int execute(ExecutionContext context) {
    ImmutableList<Path> list = inputs.get();
    try (
        OutputStream out = filesystem.newFileOutputStream(output);
    ) {
      for (Path p : list) {
        filesystem.copyToOutputStream(p, out);
      }
      out.flush();
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
    ImmutableList<Path> list = inputs.get();
    ImmutableList.Builder<String> desc = ImmutableList.builder();
    desc.add(getShortName());
    for (Path p : list) {
      desc.add(p.toString());
    }
    desc.add(">");
    desc.add(output.toString());
    return Joiner.on(" ").join(desc.build());

  }
}
