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

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.jvm.java.JavaLibraryBuilder;
import com.facebook.buck.jvm.java.MavenPublishable;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeSourcePath;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import org.eclipse.aether.deployment.DeploymentException;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public class PublisherIntegrationTest {

  @Rule
  public TemporaryPaths temp = new TemporaryPaths();

  private static Path localRepo;
  private TestPublisher publisher;

  @BeforeClass
  public static void setUpStatic() throws Exception {
    Path testDataDir = TestDataHelper.getTestDataDirectory(PublisherIntegrationTest.class);
    localRepo = testDataDir.resolve("first-party");
  }

  @After
  public void shutDownHttpd() throws Exception {
    publisher.close();
  }

  @Before
  public void setUp() throws Exception {
    publisher = TestPublisher.create(temp);
  }

  @Test
  public void testPublishFiles() throws Exception {
    String groupId = "com.example";
    String artifactName = "no-deps";
    String version = "1.0";
    String extension = "jar";

    Path artifactDir = localRepo.resolve(artifactName);
    String fileNameTemplate = String.format("%s-%s.%%s", artifactName, version);
    File jar = artifactDir.resolve(String.format(fileNameTemplate, extension)).toFile();
    File pom = artifactDir.resolve(String.format(fileNameTemplate, "pom")).toFile();

    publisher.publish(groupId, artifactName, version, ImmutableList.of(jar, pom));

    List<String> putRequestsInvoked = publisher.getPutRequestsHandler().getPutRequestsPaths();
    assertFalse(putRequestsInvoked.isEmpty());

    String urlTemplate = String.format(
        "/%s/%s/%s/%s-%s.%%s",
        groupId.replace('.', '/'),
        artifactName,
        version,
        artifactName,
        version);
    assertThat(putRequestsInvoked, hasItem(String.format(urlTemplate, extension)));
    assertThat(putRequestsInvoked, hasItem(String.format(urlTemplate, extension + ".sha1")));
    assertThat(putRequestsInvoked, hasItem(String.format(urlTemplate, "pom")));
    assertThat(putRequestsInvoked, hasItem(String.format(urlTemplate, "pom.sha1")));
  }

  @Test
  public void shouldInvokeSignerIfRequested()
      throws IOException, NoSuchBuildTargetException, InterruptedException, DeploymentException {
    ProjectFilesystem filesystem = new ProjectFilesystem(temp.getRoot());

    MavenPublishable publishable = (MavenPublishable)
        JavaLibraryBuilder.createBuilder(BuildTargetFactory.newInstance("//:lib#maven"), filesystem)
            .setMavenCoords("com.example:lib:1.0")
            .addSrc(new FakeSourcePath("Foo.java"))
            .build(new BuildRuleResolver(
                TargetGraph.EMPTY,
                new DefaultTargetNodeToBuildRuleTransformer()));
    // Ensure that the output has been created, as if we'd run the build
    filesystem.createParentDirs(publishable.getPathToOutput());
    filesystem.touch(publishable.getPathToOutput());

    // We also need to have the signature file because we can't know that gpg is available. By the
    // time the publisher is run, we expect a set of output files to have been created. Each
    // output file will have a ".asc" file generated by invoking the process executor. Set
    // everything up.
    Set<String> signatures = new HashSet<>();
    ImmutableMap.Builder<ProcessExecutorParams, FakeProcess> commandBuilder =
        ImmutableMap.builder();
    ImmutableSet<String> outputs = ImmutableSet.of(
        publishable.getPathToOutput().toString(),
        "buck-out/gen/lib#maven.pom");
    for (String out : outputs) {
      Path path = filesystem.getPathForRelativePath(out);
      Path signed = filesystem.getPathForRelativePath(out + ".asc");

      ProcessExecutorParams signingCommand = ProcessExecutorParams.builder()
          .addCommand("gpg", "-ab", "--batch", "--passphrase", "passphrase", path.toString())
          .build();
      commandBuilder.put(
          signingCommand,
          new FakeProcess(0, "", ""));
      signatures.add(signed.toString());

      filesystem.touch(path);
    }
    ImmutableMap<ProcessExecutorParams, FakeProcess> commands = commandBuilder.build();
    FakeProcessExecutor executor = new FakeProcessExecutor(commands);

    Publisher publisher = new Publisher(
        filesystem,
        Optional.of(temp.newFolder().toUri().toURL()),
        Optional.empty(),
        Optional.empty(),
        Optional.of("passphrase"),
        false) {
      @Override
      protected ProcessExecutor createProcessExecutor(PrintStream stdout, PrintStream stderr) {
        try {
          for (String signature : signatures) {
            Path path = filesystem.getRootPath().getFileSystem().getPath(signature);
            filesystem.touch(path);
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        return executor;
      }
    };

    publisher.publish(ImmutableSet.of(publishable));

    for (ProcessExecutorParams params : commands.keySet()) {
      assertTrue(executor.isProcessLaunched(params));
    }
  }
}
