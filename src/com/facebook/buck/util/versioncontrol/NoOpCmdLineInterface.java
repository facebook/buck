/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.util.versioncontrol;

/***
 * This is used if the project being built doesn't use a supported VCS.
 */
public class NoOpCmdLineInterface implements VersionControlCmdLineInterface {
  @Override
  public boolean isSupportedVersionControlSystem() {
    return false;
  }

  @Override
  public String revisionId(String name)
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public String currentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public String commonAncestor(
      String revisionIdOne,
      String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException {
    return "";
  }

  @Override
  public boolean hasWorkingDirectoryChanges()
      throws VersionControlCommandFailedException, InterruptedException {
    return false;
  }

  @Override
  public long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return 0;
  }
}
