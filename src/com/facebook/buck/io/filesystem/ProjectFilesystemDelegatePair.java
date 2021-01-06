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

package com.facebook.buck.io.filesystem;

/**
 * A wrapper around two ProjectFilesystemDelegates where generalDelegate is the typical delegate
 * that is used and buckOutDelegate is the delegate used in the case where we know we're running in
 * a buck-out directory, so we can skip the usual check if we're in an Eden mounted directory.
 */
public class ProjectFilesystemDelegatePair {

  private final ProjectFilesystemDelegate generalDelegate;
  private final ProjectFilesystemDelegate buckOutDelegate;

  public ProjectFilesystemDelegatePair(
      ProjectFilesystemDelegate defaultDelegate, ProjectFilesystemDelegate buckOutDelegate) {
    this.generalDelegate = defaultDelegate;
    this.buckOutDelegate = buckOutDelegate;
  }

  public ProjectFilesystemDelegate getGeneralDelegate() {
    return generalDelegate;
  }

  public ProjectFilesystemDelegate getBuckOutDelegate() {
    return buckOutDelegate;
  }
}
