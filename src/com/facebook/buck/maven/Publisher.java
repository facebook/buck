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

package com.facebook.buck.maven;

import com.google.common.base.Preconditions;
import com.google.common.io.Files;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.deployment.DeployRequest;
import org.eclipse.aether.deployment.DeploymentException;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.locator.ServiceLocator;
import org.eclipse.aether.util.artifact.SubArtifact;

import java.io.File;
import java.nio.file.Path;

public class Publisher {

  private final ServiceLocator locator;
  private final LocalRepository localRepo;
  private final RemoteRepository remoteRepo;

  public Publisher(
      Path localRepoPath,
      String remoteRepoUrl) {
    this.localRepo = new LocalRepository(localRepoPath.toFile());
    this.remoteRepo = AetherUtil.toRemoteRepository(remoteRepoUrl);
    this.locator = AetherUtil.initServiceLocator();
  }

  public void publish(
      String groupId,
      String artifactId,
      String version,
      File... toPublish)
      throws DeploymentException {
    publish(new DefaultArtifact(groupId, artifactId, "", version), toPublish);
  }

  /**
   * @param descriptor an {@link Artifact}, holding the maven coordinates for the published files
   *                   less the extension that is to be derived from the files.
   *                   The {@code descriptor} itself will not be published as is, and the
   *                   {@link File} attached to it (if any) will be ignored.
   * @param toPublish {@link File}(s) to be published using the given coordinates. The filename
   *                  extension of each given file will be used as a maven "extension" coordinate
   */
  public void publish(Artifact descriptor, File... toPublish) throws DeploymentException {
    Artifact[] artifacts = new Artifact[toPublish.length];
    for (int i = 0; i < toPublish.length; i++) {
      File file = toPublish[i];
      artifacts[i] = new SubArtifact(
          descriptor,
          descriptor.getClassifier(),
          Files.getFileExtension(file.getAbsolutePath()),
          file);
    }
    publish(artifacts);
  }

  /**
   * @param toPublish each {@link Artifact} must contain a file, that will be published under maven
   *                  coordinates in the corresponding {@link Artifact}.
   * @see Artifact#setFile
   */
  public void publish(Artifact... toPublish) throws DeploymentException {
    RepositorySystem repoSys = locator.getService(RepositorySystem.class);

    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    session.setLocalRepositoryManager(repoSys.newLocalRepositoryManager(session, localRepo));
    session.setReadOnly();

    DeployRequest deployRequest = new DeployRequest().setRepository(remoteRepo);
    for (Artifact artifact : toPublish) {
      File file = artifact.getFile();
      Preconditions.checkNotNull(file);
      Preconditions.checkArgument(file.exists());

      deployRequest.addArtifact(artifact);
    }

    repoSys.deploy(session, deployRequest);
  }
}
