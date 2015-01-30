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

import com.facebook.buck.io.MorePaths;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Charsets;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

import org.w3c.dom.DOMImplementation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.Map;
import java.util.Stack;
import java.util.TreeMap;

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
  private final TreeMap<String, WorkspaceNode> children;

  private static class WorkspaceNode {

  }

  private static class WorkspaceGroup extends WorkspaceNode {
    private final TreeMap<String, WorkspaceNode> children;

    WorkspaceGroup() {
      this.children = new TreeMap<>();
    }

    public Map<String, WorkspaceNode> getChildren() {
      return children;
    }
  }

  private static class WorkspaceFileRef extends WorkspaceNode {
    private final Path path;

    WorkspaceFileRef(Path path) {
      this.path = path;
    }

    public Path getPath() {
      return path;
    }
  }

  public WorkspaceGenerator(
      ProjectFilesystem projectFilesystem,
      String workspaceName,
      Path outputDirectory) {
    this.projectFilesystem = projectFilesystem;
    this.workspaceName = workspaceName;
    this.outputDirectory = outputDirectory;
    this.children = new TreeMap<>();
  }

  /**
   * Adds a reference to a project file to the generated workspace.
   *
   * @param path Path to the referenced project file in the repository.
   */
  public void addFilePath(Path path) {
    path = path.normalize();

    Optional<Path> groupPath = Optional.absent();
    // We skip the last name before the file name as it's usually the same as the project name, and
    // adding a group would add an unnecessary level of nesting. We don't check whether it's the
    // same or not to avoid inconsistent behaviour: this will result in all projects to show up in a
    // group with the path of their grandparent directory in all cases.
    if (path.getNameCount() > 2) {
      groupPath = Optional.of(path.subpath(0, path.getNameCount() - 2));
    }
    addFilePath(path, groupPath);
  }

  /**
   * Adds a reference to a project file to the group hierarchy of the generated workspace.
   *
   * @param path Path to the referenced project file in the repository.
   * @param groupPath Path in the group hierarchy of the generated workspace where
   *                  the reference will be placed.
   *                  If absent, the project reference is placed to the root of the workspace.
   */
  public void addFilePath(Path path, Optional<Path> groupPath) {
    Map<String, WorkspaceNode> children = this.children;
    if (groupPath.isPresent()) {
      for (Path groupPathPart : groupPath.get()) {
        String groupName = groupPathPart.toString();
        WorkspaceNode node = children.get(groupName);
        WorkspaceGroup group;
        if (node instanceof WorkspaceFileRef) {
          throw new HumanReadableException(
              "Invalid workspace: a group and a project have the same name: %s", groupName);
        } else if (node == null) {
          group = new WorkspaceGroup();
          children.put(groupName, group);
        } else if (node instanceof WorkspaceGroup) {
          group = (WorkspaceGroup) node;
        } else {
          // Unreachable
          throw new HumanReadableException(
              "Expected a workspace to only contain groups and file references");
        }
        children = group.getChildren();
      }
    }
    children.put(path.toString(), new WorkspaceFileRef(path));
  }

  private void walkNodeTree(FileVisitor<Map.Entry<String, WorkspaceNode>> visitor)
      throws IOException{
    Stack<Iterator<Map.Entry<String, WorkspaceNode>>> iterators = new Stack<>();
    Stack<Map.Entry<String, WorkspaceNode>> groups = new Stack<>();
    iterators.push(this.children.entrySet().iterator());
    while (!iterators.isEmpty()) {
      if (!iterators.peek().hasNext()) {
        if (groups.isEmpty()) {
          break;
        }
        visitor.postVisitDirectory(groups.pop(), null);
        iterators.pop();
        continue;
      }
      Map.Entry<String, WorkspaceNode> nextEntry = iterators.peek().next();
      WorkspaceNode nextNode = nextEntry.getValue();
      if (nextNode instanceof WorkspaceGroup) {
        visitor.preVisitDirectory(nextEntry, null);
        WorkspaceGroup nextGroup = (WorkspaceGroup) nextNode;
        groups.push(nextEntry);
        iterators.push(nextGroup.getChildren().entrySet().iterator());
      } else if (nextNode instanceof WorkspaceFileRef) {
        visitor.visitFile(nextEntry, null);
      } else {
        // Unreachable
        throw new HumanReadableException(
            "Expected a workspace to only contain groups and file references");
      }
    }
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
    final Document doc = domImplementation.createDocument(
        /* namespaceURI */ null,
        "Workspace",
        /* docType */ null);
    doc.setXmlVersion("1.0");

    Element rootElem = doc.getDocumentElement();
    rootElem.setAttribute("version", "1.0");

    final Stack<Element> groups = new Stack<>();
    groups.push(rootElem);

    FileVisitor<Map.Entry<String, WorkspaceNode>> visitor =
        new FileVisitor<Map.Entry<String, WorkspaceNode>>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Map.Entry<String, WorkspaceNode> dir,
              BasicFileAttributes attrs) throws IOException {
            Preconditions.checkArgument(dir.getValue() instanceof WorkspaceGroup);
            Element element = doc.createElement("Group");
            element.setAttribute("location", "container:");
            element.setAttribute("name", dir.getKey());
            groups.peek().appendChild(element);
            groups.push(element);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Map.Entry<String, WorkspaceNode> file,
              BasicFileAttributes attrs) throws IOException {
            Preconditions.checkArgument(file.getValue() instanceof WorkspaceFileRef);
            WorkspaceFileRef fileRef = (WorkspaceFileRef) file.getValue();
            Element element = doc.createElement("FileRef");
            element.setAttribute(
                "location",
                "container:" +
                    outputDirectory.normalize().relativize(fileRef.getPath()).toString());
            groups.peek().appendChild(element);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(
              Map.Entry<String, WorkspaceNode> file,
              IOException exc) throws IOException {
            return FileVisitResult.TERMINATE;
          }

          @Override
          public FileVisitResult postVisitDirectory(
              Map.Entry<String, WorkspaceNode> dir, IOException exc) throws IOException {
            groups.pop();
            return FileVisitResult.CONTINUE;
          }
        };

    walkNodeTree(visitor);

    Path projectWorkspaceDir = outputDirectory.resolve(workspaceName + ".xcworkspace");
    projectFilesystem.mkdirs(projectWorkspaceDir);
    Path serializedWorkspace = projectWorkspaceDir.resolve("contents.xcworkspacedata");
    try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
      DOMSource source = new DOMSource(doc);
      StreamResult result = new StreamResult(outputStream);
      transformer.transform(source, result);
      String contentsToWrite = outputStream.toString();
      if (MorePaths.fileContentsDiffer(
          new ByteArrayInputStream(contentsToWrite.getBytes(Charsets.UTF_8)),
          serializedWorkspace,
          projectFilesystem)) {
        projectFilesystem.writeContentsToPath(contentsToWrite, serializedWorkspace);
      }
    } catch (TransformerException e) {
      throw new RuntimeException(e);
    }
    return projectWorkspaceDir;
  }
}
