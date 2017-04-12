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
import com.facebook.buck.model.Pair;
import com.facebook.buck.util.ProcessExecutorFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.nio.file.Path;
import java.util.Optional;

import javax.annotation.Nullable;

public class DelegatingVersionControlCmdLineInterface
    implements VersionControlCmdLineInterface {
  private static final Logger LOG = Logger.get(DelegatingVersionControlCmdLineInterface.class);

  private final Path projectRoot;
  private final ProcessExecutorFactory processExecutorFactory;
  private final String hgCmd;
  private final ImmutableMap<String, String> environment;
  @Nullable
  private VersionControlCmdLineInterface delegate;

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
        new HgCmdLineInterface(
            processExecutorFactory,
            projectRoot,
            hgCmd,
            environment);

    try {
      hgCmdLineInterface.currentRevisionId();
      LOG.debug("Using HgCmdLineInterface.");
      delegate = hgCmdLineInterface;
      return delegate;
    } catch (VersionControlCommandFailedException ex) {
      LOG.warn("Mercurial is the only VCS supported for VCS stats generation, however " +
          "current project (which has enabled VCS stats generation in its .buckconfig) " +
          "does not appear to be a Mercurial repository: \n%s", ex);
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
  public String revisionId(String name)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().revisionId(name);
  }

  @Override
  public Optional<String> revisionIdOrAbsent(String name) throws InterruptedException {
    return getDelegate().revisionIdOrAbsent(name);
  }

  @Override
  public String currentRevisionId()
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().currentRevisionId();
  }

  @Override
  public String commonAncestor(
      String revisionIdOne,
      String revisionIdTwo)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().commonAncestor(revisionIdOne, revisionIdTwo);
  }

  @Override
  public Pair<String, Long> commonAncestorAndTS(
      String revisionIdOne,
      String revisionIdTwo) throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().commonAncestorAndTS(revisionIdOne, revisionIdTwo);
  }

  @Override
  public Optional<String> commonAncestorOrAbsent(
      String revisionIdOne,
      String revisionIdTwo) throws InterruptedException {
    return getDelegate().commonAncestorOrAbsent(revisionIdOne, revisionIdTwo);
  }

  @Override
  public Optional<Pair<String, Long>> commonAncestorAndTSOrAbsent(
      String revisionIdOne, String revisionIdTwo) throws InterruptedException {
    return getDelegate().commonAncestorAndTSOrAbsent(revisionIdOne, revisionIdTwo);
  }

  @Override
  public String diffBetweenRevisions(
      String baseRevision,
      String tipRevision)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().diffBetweenRevisions(baseRevision, tipRevision);
  }

  @Override
  public Optional<String> diffBetweenRevisionsOrAbsent(
      String baseRevision,
      String tipRevision)
      throws InterruptedException {
    return getDelegate().diffBetweenRevisionsOrAbsent(baseRevision, tipRevision);
  }

  @Override
  public ImmutableSet<String> changedFiles(String fromRevisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().changedFiles(fromRevisionId);
  }

  @Override
  public long timestampSeconds(String revisionId)
      throws VersionControlCommandFailedException, InterruptedException {
    return getDelegate().timestampSeconds(revisionId);
  }

  @Override
  public ImmutableMap<String, String> bookmarksRevisionsId(ImmutableSet<String> bookmarks)
      throws InterruptedException, VersionControlCommandFailedException {
    return getDelegate().bookmarksRevisionsId(bookmarks);
  }

}
