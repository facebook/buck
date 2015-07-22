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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
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
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;

public class Publisher {

  public static final String MAVEN_CENTRAL_URL = "https://repo1.maven.org/maven2";
  private static final URL MAVEN_CENTRAL;
  static {
    try {
      MAVEN_CENTRAL = new URL(MAVEN_CENTRAL_URL);
    } catch (MalformedURLException e) {
      throw Throwables.propagate(e);
    }
  }
  private static final Logger LOG = Logger.get(Publisher.class);

  private final ServiceLocator locator;
  private final LocalRepository localRepo;
  private final RemoteRepository remoteRepo;

  public Publisher(ProjectFilesystem repositoryFilesystem, Optional<URL> remoteRepoUrl) {
    this(repositoryFilesystem.getRootPath(), remoteRepoUrl);
  }

  /**
   * @param localRepoPath Typically obtained as
   *                      {@link com.facebook.buck.io.ProjectFilesystem#getRootPath}
   * @param remoteRepoUrl Canonically {@link #MAVEN_CENTRAL_URL}
   */
  public Publisher(Path localRepoPath, Optional<URL> remoteRepoUrl) {
    this.localRepo = new LocalRepository(localRepoPath.toFile());
    this.remoteRepo = AetherUtil.toRemoteRepository(remoteRepoUrl.or(MAVEN_CENTRAL));
    this.locator = AetherUtil.initServiceLocator();
  }

  /**
   * @param coords Buildr-style artifact reference, e.g. "com.example:foo:1.0"
   *
   * @see DefaultArtifact#DefaultArtifact(String)
   */
  public void publish(String coords, File... toPublish) throws DeploymentException {
    publish(new DefaultArtifact(coords), toPublish);
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
    String providedExtension = descriptor.getExtension();
    if (!providedExtension.isEmpty()) {
      LOG.warn(
          "Provided extension %s of artifact %s to be published will be ignored. The extensions " +
              "of the provided file(s) will be used",
          providedExtension,
          descriptor);
    }
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
    RepositorySystem repoSys = Preconditions.checkNotNull(
        locator.getService(RepositorySystem.class));

    DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
    session.setLocalRepositoryManager(repoSys.newLocalRepositoryManager(session, localRepo));
    session.setReadOnly();

    DeployRequest deployRequest = new DeployRequest().setRepository(remoteRepo);
    for (Artifact artifact : toPublish) {
      File file = artifact.getFile();
      Preconditions.checkNotNull(file);
      Preconditions.checkArgument(file.exists(), "No such file: %s", file.getAbsolutePath());

      deployRequest.addArtifact(artifact);
    }

    repoSys.deploy(session, deployRequest);
  }
}
