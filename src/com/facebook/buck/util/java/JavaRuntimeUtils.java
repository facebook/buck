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

package com.facebook.buck.util.java;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.cmd.CmdWrapperForScriptWithSpaces;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Suppliers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.function.Supplier;

/** Utilities method related to java runtime */
public class JavaRuntimeUtils {

  private static final Logger LOG = Logger.get(JavaRuntimeUtils.class);

  private static final String JAVA_HOME_SYSTEM_PROPERTY_NAME = "java.home";
  private static final String DEFAULT_EXECUTABLE_NAME = "java";

  private static final Supplier<Optional<Path>> JAVA_BIN_COMMAND =
      Suppliers.memoize(
          () -> {
            // if `java.home` is set then use it
            String javaHome = System.getProperty(JAVA_HOME_SYSTEM_PROPERTY_NAME);
            LOG.info("Java home: %s", javaHome);
            if (javaHome != null) {
              boolean isWindows = Platform.detect() == Platform.WINDOWS;
              String binaryExtension = isWindows ? ".exe" : "";
              Path javaBinPath =
                  Paths.get(javaHome, "bin", DEFAULT_EXECUTABLE_NAME + binaryExtension);
              if (Files.exists(javaBinPath)) {
                boolean needToWrapWithCmdScript = isWindows && javaBinPath.toString().contains(" ");
                if (!needToWrapWithCmdScript) {
                  return Optional.of(javaBinPath);
                }

                CmdWrapperForScriptWithSpaces cmdWrapperForScriptWithSpaces =
                    new CmdWrapperForScriptWithSpaces(javaBinPath);
                return cmdWrapperForScriptWithSpaces.getCmdPath();
              }
            }

            return Optional.empty();
          });

  /** Returns a path for java binary used to launch buck */
  public static String getBucksJavaBinCommand() {
    return JAVA_BIN_COMMAND.get().map(Path::toString).orElse(DEFAULT_EXECUTABLE_NAME);
  }
}
