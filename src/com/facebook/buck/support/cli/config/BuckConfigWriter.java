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

package com.facebook.buck.support.cli.config;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.log.InvocationInfo;
import com.facebook.buck.logd.client.LogStreamFactory;
import com.facebook.buck.logd.proto.LogType;
import com.facebook.buck.util.BuckConstant;
import com.facebook.buck.util.json.ObjectMappers;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.collect.ImmutableMap;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/** Simple class that writes out the {@link BuckConfig} to a file for a specific invocation */
public class BuckConfigWriter {

  private BuckConfigWriter() {}

  /**
   * Writes the configuration out to a standard location as json for a specific command
   *
   * <p>Currently this is of the form:
   *
   * <pre>
   *   {
   *     "settings": {
   *       "section": {
   *         "key": "value"
   *         "key2": "value2"
   *       },
   *       "other_section": {
   *         "key3": "value3",
   *         "key4": "true"
   *     }
   *   }
   * </pre>
   *
   * In the future, the source of each setting will be visible with a structure like this:
   *
   * <pre>
   *   {
   *     "settings": {
   *       "section": {
   *         "key": "value"
   *         "key2": "value2"
   *       },
   *       "other_section": {
   *         "key3": "value3",
   *         "key4": "true"
   *     },
   *     "sources": {
   *       "$path_to_config_file": {
   *         "section": {
   *           "key": "value"
   *           "key2": "value2"
   *         }
   *       }
   *     }
   *   }
   * </pre>
   *
   * @param rootPath The root of the project that contains buck-out
   * @param info The invocation info. Used to find the log directories to write into
   * @param config The configuration to write to disk
   * @param logStreamFactory log stream factory implementation depending on whether logd is enabled
   * @throws IOException The file could not be written to
   */
  public static void writeConfig(
      Path rootPath, InvocationInfo info, BuckConfig config, LogStreamFactory logStreamFactory)
      throws IOException {
    Path logDirectory = rootPath.resolve(info.getLogDirectoryPath());
    Files.createDirectories(logDirectory);
    String logFilePath = logDirectory.resolve(BuckConstant.CONFIG_JSON_FILE_NAME).toString();
    try (BufferedWriter jsonOut =
        new BufferedWriter(
            new OutputStreamWriter(
                logStreamFactory.createLogStream(logFilePath, LogType.BUCK_CONFIG_LOG)))) {
      ObjectMappers.WRITER
          .withoutFeatures(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
          .writeValue(
              jsonOut, ImmutableMap.of("settings", config.getConfig().getRawConfig().getValues()));
    }
  }
}
