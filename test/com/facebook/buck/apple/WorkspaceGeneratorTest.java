/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.apple;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasXPath;
import static org.junit.Assert.assertThat;

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.timing.SettableFakeClock;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class WorkspaceGeneratorTest {
  private SettableFakeClock clock;
  private ProjectFilesystem projectFilesystem;
  private WorkspaceGenerator generator;

  @Before
  public void setUp() {
    clock = new SettableFakeClock(0, 0);
    projectFilesystem = new FakeProjectFilesystem(clock);
    generator = new WorkspaceGenerator(projectFilesystem, "ws", Paths.get("."));
  }

  @Test
   public void testFlatWorkspaceContainsCorrectFileRefs() throws Exception {
    generator.addFilePath(Paths.get("./Project.xcodeproj"));
    Path workspacePath = generator.writeWorkspace();
    Path contentsPath = workspacePath.resolve("contents.xcworkspacedata");

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document workspace = dBuilder.parse(projectFilesystem.newFileInputStream(contentsPath));
    assertThat(workspace, hasXPath("/Workspace[@version = \"1.0\"]"));
    assertThat(workspace, hasXPath("/Workspace/FileRef/@location",
            equalTo("container:Project.xcodeproj")));
  }

  @Test
  public void testNestedWorkspaceContainsCorrectFileRefs() throws Exception {
    generator.addFilePath(Paths.get("./grandparent/parent/Project.xcodeproj"));
    Path workspacePath = generator.writeWorkspace();
    Path contentsPath = workspacePath.resolve("contents.xcworkspacedata");

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document workspace = dBuilder.parse(projectFilesystem.newFileInputStream(contentsPath));
    assertThat(workspace, hasXPath("/Workspace/Group[@name=\"grandparent\"]/FileRef/@location",
            equalTo("container:grandparent/parent/Project.xcodeproj")));
  }

  @Test
  public void testWorkspaceWithCustomFilePaths() throws Exception {
    generator.addFilePath(
        Paths.get("grandparent/parent/Project.xcodeproj"),
        Optional.of(Paths.get("VirtualParent")));
    Path workspacePath = generator.writeWorkspace();
    Path contentsPath = workspacePath.resolve("contents.xcworkspacedata");

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document workspace = dBuilder.parse(projectFilesystem.newFileInputStream(contentsPath));
    assertThat(
        workspace,
        hasXPath("/Workspace/Group[@name=\"VirtualParent\"]/FileRef/@location",
        equalTo("container:grandparent/parent/Project.xcodeproj")));
  }

  @Test
  public void testWorkspaceContainsNodeInAlphabeticalOrder() throws Exception {
    generator.addFilePath(Paths.get("./2/parent/C.xcodeproj"));
    generator.addFilePath(Paths.get("./2/parent/B.xcodeproj"));
    generator.addFilePath(Paths.get("./2/parent/D.xcodeproj"));
    generator.addFilePath(Paths.get("./1/parent/E.xcodeproj"));
    generator.addFilePath(Paths.get("./3/parent/A.xcodeproj"));

    Path workspacePath = generator.writeWorkspace();
    Path contentsPath = workspacePath.resolve("contents.xcworkspacedata");

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document workspace = dBuilder.parse(projectFilesystem.newFileInputStream(contentsPath));

    Node workspaceNode = workspace.getChildNodes().item(0);

    ImmutableList.Builder<String> groupsBuilder = ImmutableList.builder();
    for (int i = 0; i < workspaceNode.getChildNodes().getLength(); ++i) {
      groupsBuilder.add(
          workspaceNode
              .getChildNodes()
              .item(i)
              .getAttributes()
              .getNamedItem("name")
              .getNodeValue());
    }
    assertThat(groupsBuilder.build(), equalTo(ImmutableList.of("1", "2", "3")));

    Node secondGroup = workspaceNode.getChildNodes().item(1);

    assertThat(secondGroup.getAttributes().getNamedItem("name").getNodeValue(), equalTo("2"));

    ImmutableList.Builder<String> projectsBuilder = ImmutableList.builder();
    for (int i = 0; i < secondGroup.getChildNodes().getLength(); ++i) {
      projectsBuilder.add(
          secondGroup
              .getChildNodes()
              .item(i)
              .getAttributes()
              .getNamedItem("location")
              .getNodeValue());
    }
    assertThat(
        projectsBuilder.build(),
        equalTo(
            ImmutableList.of(
                "container:2/parent/B.xcodeproj",
                "container:2/parent/C.xcodeproj",
                "container:2/parent/D.xcodeproj")));
  }

  @Test
  public void workspaceIsRewrittenIfContentsHaveChanged() throws IOException {
    {
      generator.addFilePath(Paths.get("./Project.xcodeproj"));
      clock.setCurrentTimeMillis(49152);
      Path workspacePath = generator.writeWorkspace();
      assertThat(
          projectFilesystem.getLastModifiedTime(workspacePath.resolve("contents.xcworkspacedata")),
          equalTo(49152L));
    }

    {
      WorkspaceGenerator generator2 = new WorkspaceGenerator(
          projectFilesystem,
          "ws",
          Paths.get("."));
      generator2.addFilePath(Paths.get("./Project2.xcodeproj"));
      clock.setCurrentTimeMillis(64738);
      Path workspacePath2 = generator2.writeWorkspace();
      assertThat(
          projectFilesystem.getLastModifiedTime(workspacePath2.resolve("contents.xcworkspacedata")),
          equalTo(64738L));
    }
  }

  @Test
  public void workspaceIsNotRewrittenIfContentsHaveNotChanged() throws IOException {
    {
      generator.addFilePath(Paths.get("./Project.xcodeproj"));
      clock.setCurrentTimeMillis(49152);
      Path workspacePath = generator.writeWorkspace();
      assertThat(
          projectFilesystem.getLastModifiedTime(workspacePath.resolve("contents.xcworkspacedata")),
          equalTo(49152L));
    }

    {
      WorkspaceGenerator generator2 = new WorkspaceGenerator(
          projectFilesystem,
          "ws",
          Paths.get("."));
      generator2.addFilePath(Paths.get("./Project.xcodeproj"));
      clock.setCurrentTimeMillis(64738);
      Path workspacePath2 = generator2.writeWorkspace();
      assertThat(
          projectFilesystem.getLastModifiedTime(workspacePath2.resolve("contents.xcworkspacedata")),
          equalTo(49152L));
    }
  }
}
