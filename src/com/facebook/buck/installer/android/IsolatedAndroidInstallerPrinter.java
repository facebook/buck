/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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

package com.facebook.buck.installer;

import com.facebook.buck.android.AndroidInstallPrinter;
import java.util.logging.Level;
import java.util.logging.Logger; // NOPMD

/** Handles logging for buckv2 android installs */
class IsolatedAndroidInstallerPrinter implements AndroidInstallPrinter {
  private Logger logger;

  public IsolatedAndroidInstallerPrinter(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void printMessage(String message) {
    logger.log(Level.INFO, message);
  }

  @Override
  public void printSuccess(String successMessage) {
    logger.log(Level.INFO, successMessage);
  }

  @Override
  public void printError(String failureMessage) {
    logger.log(Level.WARNING, failureMessage);
  }
}
