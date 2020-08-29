/*
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.cxx.toolchain.objectfile;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.file.FileAttributesScrubber;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Scrubber to strip the binary of its debug symbol table. */
public class StripDebugSymbolTableScrubber implements FileAttributesScrubber {

  private static final Logger LOG = Logger.get(StripDebugSymbolTableScrubber.class);

  @Override
  public void scrubFileWithPath(
      Path path, ProcessExecutor processExecutor, ImmutableMap<String, String> environment)
      throws IOException, InterruptedException {

    ProcessExecutorParams.Builder builder = ProcessExecutorParams.builder();
    builder.setCommand(Arrays.asList("strip", "-S", path.toAbsolutePath().toString()));
    builder.setEnvironment(environment);

    try {
      processExecutor.launchAndExecute(builder.build());
    } catch (Exception exception) {
      LOG.error(exception.getMessage());
      throw exception;
    }
  }
}
