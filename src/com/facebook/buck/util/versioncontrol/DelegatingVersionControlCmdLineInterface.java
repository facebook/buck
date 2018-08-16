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

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.util.ProcessExecutorFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

public class DelegatingVersionControlCmdLineInterface implements VersionControlCmdLineInterface {
  private static final Logger LOG = Logger.get(DelegatingVersionControlCmdLineInterface.class);

  private final Path projectRoot;
  private final ProcessExecutorFactory processExecutorFactory;
  private final String hgCmd;
  private final ImmutableMap<String, String> environment;
  @Nullable private VersionControlCmdLineInterface delegate;

  public DelegatingVersionControlCmdLineInterface(
      Path projectRoot,
      ProcessExecutorFactory processExecutorFactory,
      String hgCmd,
      ImmutableMap<String, String> environment) {
    this.projectRoot = projectRoot;
    this.processExecutorFactory = processExecutorFactory;
    this.hgCmd = hgCmd;
    this.environment = environment;
  }

  private VersionControlCmdLineInterface getDelegate() throws InterruptedException {
    if (delegate != null) {
      return delegate;
    }
    HgCmdLineInterface hgCmdLineInterface =
        new HgCmdLineInterface(processExecutorFactory, projectRoot, hgCmd, environment);

    try {
      hgCmdLineInterface.currentRevisionId();
      LOG.debug("Using HgCmdLineInterface.");
      delegate = hgCmdLineInterface;
      return delegate;
    } catch (VersionControlCommandFailedException ex) {
      LOG.warn(
          "Mercurial is the only VCS supported for VCS stats generation, however "
              + "current project (which has enabled VCS stats generation in its .buckconfig) "
              + "does not appear to be a Mercurial repository: \n%s",
          ex);
    }

    LOG.debug("Using NoOpCmdLineInterface.");
    delegate = new NoOpCmdLineInterface();
    return delegate;
  }

  @Override
  public boolean isSupportedVersionControlSystem() throws InterruptedException {
    return getDelegate().isSupportedVersionControlSystem();
  }

  @Override
  public VersionControlSupplier<InputStream> diffBetweenRevisions(
      String baseRevision, String tipRevision)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().diffBetweenRevisions(baseRevision, tipRevision);
  }

  @Override
  public Optional<VersionControlSupplier<InputStream>> diffBetweenRevisionsOrAbsent(
      String baseRevision, String tipRevision) throws InterruptedException {
    return getDelegate().diffBetweenRevisionsOrAbsent(baseRevision, tipRevision);
  }

  @Override
  public ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().changedFiles(fromRevisionId);
  }

  @Override
  public FastVersionControlStats fastVersionControlStats()
      throws InterruptedException, VersionControlCommandFailedException {
    return getDelegate().fastVersionControlStats();
  }
}
