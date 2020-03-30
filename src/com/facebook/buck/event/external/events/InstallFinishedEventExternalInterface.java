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

package com.facebook.buck.event.external.events;
/**
 * Describes an install finished event sent by buck. This type is intended to be used by external
 * applications (like the Intellij Buck plugin) to deserialize events coming from the webserver.
 */
public interface InstallFinishedEventExternalInterface extends BuckEventExternalInterface {
  // Sent when an install has finished
  String INSTALL_FINISHED = "InstallFinished";

  /** @return the package name of the installed application. */
  String getPackageName();

  /** @return the success status of the install action. */
  boolean isSuccess();
}
