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

package com.facebook.buck.apple.xcode;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasXPath;
import static org.junit.Assert.assertThat;

import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.util.ProjectFilesystem;

import org.junit.Before;
import org.junit.Test;
import org.w3c.dom.Document;

import java.nio.file.Path;
import java.nio.file.Paths;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class WorkspaceGeneratorTest {
  private final ProjectFilesystem projectFilesystem = new FakeProjectFilesystem();
  private WorkspaceGenerator generator;

  @Before
  public void setUp() {
    generator = new WorkspaceGenerator(
        projectFilesystem,
        "ws",
        Paths.get("."));
  }

  @Test
   public void testFlatWorkspaceContainsCorrectFileRefs() throws Exception {
    generator.addFilePath(null, Paths.get("./Project.xcodeproj"));
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
    generator.addFilePath("group", Paths.get("./Project.xcodeproj"));
    Path workspacePath = generator.writeWorkspace();
    Path contentsPath = workspacePath.resolve("contents.xcworkspacedata");

    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
    Document workspace = dBuilder.parse(projectFilesystem.newFileInputStream(contentsPath));
    assertThat(workspace, hasXPath("/Workspace/Group[@name=\"group\"]/FileRef/@location",
            equalTo("container:Project.xcodeproj")));
  }
}
