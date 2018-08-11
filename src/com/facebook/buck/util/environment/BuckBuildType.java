/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.util.environment;

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.MoreSuppliers;
import com.google.common.io.MoreFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.function.Supplier;

public enum BuckBuildType {
  UNKNOWN,
  LOCAL_ANT,
  LOCAL_PEX,
  RELEASE_PEX,
  ;

  private static final Logger LOG = Logger.get(BuckBuildType.class);

  /**
   * Check in runtime to see what current build type is.
   *
   * <p>To run buck in different modes you can invoke: buck run buck --config build.type=enum_value
   */
  public static final Supplier<BuckBuildType> CURRENT_BUCK_BUILD_TYPE =
      MoreSuppliers.memoize(
          () -> {
            String buildTypeFilename = System.getProperty("buck.buck_build_type_info");
            if (buildTypeFilename == null) {
              return UNKNOWN;
            }
            try {
              String contents =
                  MoreFiles.asCharSource(Paths.get(buildTypeFilename), StandardCharsets.UTF_8)
                      .readFirstLine();
              return BuckBuildType.valueOf(contents);
            } catch (IOException e) {
              LOG.error(e, "Failed to read build type, using LOCAL_ANT type.");
              return LOCAL_ANT;
            }
          });
}
