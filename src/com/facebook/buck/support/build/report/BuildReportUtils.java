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

package com.facebook.buck.support.build.report;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.util.log.Logger;
import java.net.URL;
import java.util.Optional;

/** Class for utils related to creating/uploading a build report */
public final class BuildReportUtils {
  private BuildReportUtils() {}

  private static final Logger LOG = Logger.get(BuildReportUtils.class);

  /**
   * if build report is not enabled or the endpoint url is not configured then we should not upload
   * a build report and returns false.
   */
  public static boolean shouldUploadBuildReport(BuckConfig buckConfig) {
    BuildReportConfig buildReportConfig = buckConfig.getView(BuildReportConfig.class);

    if (!buildReportConfig.getEnabled()) {
      LOG.info("Build report is not enabled");
      return false;
    }

    Optional<URL> endpointUrl = buildReportConfig.getEndpointUrl();

    if (!endpointUrl.isPresent()) {
      LOG.error("Build report is enabled but its endpoint url is not configured");
      return false;
    }

    return true;
  }
}
