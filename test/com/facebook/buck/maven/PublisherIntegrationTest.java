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

import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.testutil.integration.TestDataHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class PublisherIntegrationTest {

  @Rule
  public TemporaryFolder temp = new TemporaryFolder();

  private static Path localRepo;
  private HttpdForTests httpd;
  private Publisher publisher;
  private HttpdForTests.DummyPutRequestsHandler putRequestsHandler;

  @BeforeClass
  public static void setUpStatic() throws Exception {
    Path testDataDir = TestDataHelper.getTestDataDirectory(PublisherIntegrationTest.class);
    localRepo = testDataDir.resolve("first-party");
  }

  @After
  public void shutDownHttpd() throws Exception {
    httpd.close();
  }

  @Before
  public void setUp() throws Exception {
    httpd = new HttpdForTests();
    putRequestsHandler = new HttpdForTests.DummyPutRequestsHandler();
    httpd.addHandler(putRequestsHandler);
    httpd.start();
    publisher = new Publisher(temp.newFolder().toPath(), httpd.getUri("/").toString());
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

    publisher.publish(groupId, artifactName, version, jar, pom);

    List<String> putRequestsInvoked = putRequestsHandler.getPutRequestsPaths();
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
}
