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

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.java.HasMavenCoordinates;
import com.facebook.buck.java.MavenPublishable;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSortedSet;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.xml.transform.TransformerException;

public class PomIntegrationTest extends EasyMockSupport {

  private static final MavenXpp3Writer MAVEN_XPP_3_WRITER = new MavenXpp3Writer();
  private static final MavenXpp3Reader MAVEN_XPP_3_READER = new MavenXpp3Reader();
  private static final String URL = "http://example.com";

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Test
  public void testMultipleInvocation() throws Exception{

    // Setup: deps: com.example:with-deps:jar:1.0 -> com.othercorp:no-deps:jar:1.0
    HasMavenCoordinates dep = mockMavenPublishable(
        "com.othercorp:no-deps:1.0",
        ImmutableSortedSet.<HasMavenCoordinates>of());

    MavenPublishable item = mockMavenPublishable(
        "com.example:with-deps:1.0",
        ImmutableSortedSet.of(dep));

    Path pomPath = tmp.getRootPath().resolve("pom.xml");
    File pomFile = pomPath.toFile();
    assertFalse(pomFile.exists());

    // Basic case
    Pom.generatePomFile(item, pomPath);

    Model pomModel = parseAndVerifyPomFile(pomFile);

    // Corrupt dependency data and ensure buck restores that
    removeDependencies(pomModel, pomFile);

    Pom.generatePomFile(item, pomPath);

    pomModel = parseAndVerifyPomFile(pomFile);

    // Add extra pom data and ensure buck preserves that
    pomModel.setUrl(URL);
    serializePom(pomModel, pomFile);

    Pom.generatePomFile(item, pomPath);

    pomModel = parseAndVerifyPomFile(pomFile);
    assertEquals(URL, pomModel.getUrl());
  }

  private MavenPublishable mockMavenPublishable(
      String mavenCoords,
      ImmutableSortedSet<HasMavenCoordinates> deps) {
    MavenPublishable mavenPublishable = createNiceMock(MavenPublishable.class);
    expect(mavenPublishable.getMavenCoords())
        .andReturn(Optional.fromNullable(mavenCoords))
        .anyTimes();
    expect(mavenPublishable.getMavenDeps()).andReturn(deps).anyTimes();
    replay(mavenPublishable);
    return mavenPublishable;
  }

  private static void serializePom(Model pomModel, File destination) throws IOException {
    MAVEN_XPP_3_WRITER.write(new FileWriter(destination), pomModel);
  }

  private static void removeDependencies(Model model, File pomFile)
      throws IOException, SAXException, TransformerException {
    model.setDependencies(Collections.<Dependency>emptyList());
    serializePom(model, pomFile);
  }

  /**
   * assert deps: com.example:with-deps:jar:1.0 -> com.othercorp:no-deps:jar:1.0
   */
  private static Model parseAndVerifyPomFile(File pomFile) throws Exception {
    assertTrue(pomFile.isFile());
    Model pomModel = MAVEN_XPP_3_READER.read(new FileReader(pomFile));
    assertEquals("com.example", pomModel.getGroupId());
    assertEquals("with-deps", pomModel.getArtifactId());
    assertEquals("1.0", pomModel.getVersion());
    List<Dependency> dependencies = pomModel.getDependencies();
    assertEquals(1, dependencies.size());
    Dependency dependency = dependencies.get(0);
    assertEquals("com.othercorp", dependency.getGroupId());
    assertEquals("no-deps", dependency.getArtifactId());
    assertEquals("1.0", dependency.getVersion());
    return pomModel;
  }
}
