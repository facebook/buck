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

import com.facebook.buck.log.Logger;
import com.facebook.buck.util.ProcessExecutor;

import java.nio.file.Path;

public class DefaultVersionControlCmdLineInterfaceFactory
    implements VersionControlCmdLineInterfaceFactory {
  private static final Logger LOG = Logger.get(DefaultVersionControlCmdLineInterfaceFactory.class);

  private final Path projectRoot;
  private final ProcessExecutor processExecutor;
  private final String hgCmd;

  public DefaultVersionControlCmdLineInterfaceFactory(
      Path projectRoot,
      ProcessExecutor processExecutor,
      VersionControlBuckConfig buckConfig) {
    this.projectRoot = projectRoot;
    this.processExecutor = processExecutor;
    this.hgCmd = buckConfig.getHgCmd();
  }

  @Override
  public VersionControlCmdLineInterface createCmdLineInterface() throws InterruptedException {
      HgCmdLineInterface hgCmdLineInterface =
          new HgCmdLineInterface(processExecutor, projectRoot.toFile(), hgCmd);

      try {
        hgCmdLineInterface.currentRevisionId();
        LOG.debug("Using HgCmdLineInterface.");
        return hgCmdLineInterface;
      } catch (VersionControlCommandFailedException ex) {
        LOG.warn("Mercurial is the only VCS supported for VCS stats generation, however " +
                "current project (which has enabled VCS stats generation in its .buckconfig) " +
                "does not appear to be a Mercurial repository: \n%s", ex);
      }

    LOG.debug("Using NoOpCmdLineInterface.");
    return new NoOpCmdLineInterface();
  }
}
