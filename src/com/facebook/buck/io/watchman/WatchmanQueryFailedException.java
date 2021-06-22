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

package com.facebook.buck.io.watchman;

/**
 * Watchman returned an error response.
 *
 * <p>{@link WatchmanClient} successfully returned a response, but the <a
 * href="https://facebook.github.io/watchman/docs/socket-interface.html#reporting-errors-and-warnings">response
 * contained an error</a>.
 */
public class WatchmanQueryFailedException extends Exception {

  private final String watchmanErrorMessage;
  private final WatchmanError watchmanError;

  /** @param watchmanErrorMessage The value of the {@code error} field in Watchman's response. */
  public WatchmanQueryFailedException(String watchmanErrorMessage, WatchmanError watchmanError) {
    super("Watchman query failed: " + watchmanErrorMessage);
    this.watchmanErrorMessage = watchmanErrorMessage;
    this.watchmanError = watchmanError;
  }

  public WatchmanError getWatchmanError() {
    return watchmanError;
  }

  public WatchmanFactory.NullWatchman toNullWatchman() {
    return new WatchmanFactory.NullWatchman(watchmanErrorMessage, watchmanError);
  }

  /** @return The human-readable error message reported by Watchman. */
  public String getWatchmanErrorMessage() {
    return watchmanErrorMessage;
  }
}
