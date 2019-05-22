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

package com.facebook.buck.doctor.config;

import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.Optional;

@BuckStyleValue
public interface DoctorEndpointRequest {

  Optional<BuildId> getBuildId();

  /** @return the directory where all logs are under. */
  String getLogDirPath();

  /**
   * For more information how MachineReadableLog works check {@link
   * com.facebook.buck.event.listener.MachineReadableLoggerListener}.
   *
   * @return the contents of the machine readable logs. Each line marks a different key to json.
   */
  Optional<String> getMachineReadableLog();

  /** @return the contents of the message that endpoint returned. */
  Optional<String> getEndpointMessage();

  /** @return if report command returned a redirect link this will be saved here. */
  Optional<String> getReportUrl();
}
