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

package com.facebook.buck.support.fix;

import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.json.ObjectMappers;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple class that writes a serialized to json {@link BuckFixSpec} to the log directory */
public class BuckFixSpecWriter {

  private BuckFixSpecWriter() {}

  /**
   * Writes the json representation of the fix spec to the logs directory, derived from the rootPath
   * and the InvocationInfo
   *
   * @param rootPath The root of the project that contains buck-out
   * @param info The invocation info. Used to find the log directories to write into
   * @param spec the spec to write to disk
   * @throws IOException The file could not be written to
   * @return the path to which the spec was written to
   */
  public static Path writeSpecToLogDir(Path rootPath, InvocationInfo info, BuckFixSpec spec)
      throws IOException {
    Path logDirectory = rootPath.resolve(info.getLogDirectoryPath());
    Path buckFixSpecFilePath = logDirectory.resolve(BuckConstant.BUCK_FIX_SPEC_FILE_NAME);

    writeSpec(buckFixSpecFilePath, spec);
    return buckFixSpecFilePath;
  }

  /**
   * Writes the json representation of the fix spec to be consumed by the fix script, it's usually
   * information found in logs too but in a much easier format for the script to consume, plus
   * command data that might be captured only in runtime.
   *
   * @param fixSpecPath a path to the file where the spec is to be written
   * @param spec the spec to write to disk
   * @throws IOException
   */
  public static void writeSpec(Path fixSpecPath, BuckFixSpec spec) throws IOException {
    // make sure directories in this path are created before writing to it.
    Files.createDirectories(fixSpecPath.getParent());
    try (BufferedWriter jsonOut = Files.newBufferedWriter(fixSpecPath)) {
      ObjectMappers.WRITER.writeValue(jsonOut, spec);
    }
  }
}
