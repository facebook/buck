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

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * Collects file references and generates an xcworkspace.
 */
public class WorkspaceGenerator {
  private final ProjectFilesystem projectFilesystem;
  private final String workspaceName;
  private final Path outputDirectory;
  private final Multimap<String, Path> projects;

  public WorkspaceGenerator(
      ProjectFilesystem projectFilesystem,
      String workspaceName,
      Path outputDirectory) {
    this.projectFilesystem = Preconditions.checkNotNull(projectFilesystem);
    this.workspaceName = Preconditions.checkNotNull(workspaceName);
    this.outputDirectory = Preconditions.checkNotNull(outputDirectory);
    projects = ArrayListMultimap.create();
  }

  public void addFilePath(String groupName, Path path) {
    projects.put(Strings.nullToEmpty(groupName), path);
  }

  public Path writeWorkspace() throws IOException {
    DocumentBuilder docBuilder;
    Transformer transformer;
    try {
      docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
      transformer = TransformerFactory.newInstance().newTransformer();
    } catch (ParserConfigurationException | TransformerConfigurationException e) {
      throw new RuntimeException(e);
    }

    DOMImplementation domImplementation = docBuilder.getDOMImplementation();
    Document doc = domImplementation.createDocument(
        /* namespaceURI */ null,
        "Workspace",
        /* docType */ null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("version", "1.0");

    for (String groupName : projects.keySet()) {
      Element targetElement = doc.getDocumentElement();
      if (!groupName.isEmpty()) {
        Element group = doc.createElement("Group");
        group.setAttribute("location", "container:");
        group.setAttribute("name", groupName);
        rootElem.appendChild(group);
        targetElement = group;
      }
      for (Path path : projects.get(groupName)) {
        Element fileRef = doc.createElement("FileRef");
        fileRef.setAttribute("location",
            "container:" + outputDirectory.relativize(path).toString());
        targetElement.appendChild(fileRef);
      }
    }

    Path projectWorkspaceDir = outputDirectory.resolve(workspaceName + ".xcworkspace");
    projectFilesystem.mkdirs(projectWorkspaceDir);
    Path serializedWorkspace = projectWorkspaceDir.resolve("contents.xcworkspacedata");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(outputStream);
      transformer.transform(source, result);
      projectFilesystem.writeContentsToPath(outputStream.toString(), serializedWorkspace);
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
    return projectWorkspaceDir;
  }
}
