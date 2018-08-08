/*
 * Copyright 2017-present Facebook, Inc.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Updates .idea/workspace.xml to avoid doing some operations by IntelliJ.
 *
 * <p>It updates a list of ignored files based on the list of all excluded folders from all modules
 * in a project. IntelliJ takes quadratic time to construct this list (see
 * https://youtrack.jetbrains.com/issue/IDEA-174335).
 */
public class WorkspaceUpdater {

  private static final Logger LOG = Logger.get(WorkspaceUpdater.class);

  private final ProjectFilesystem filesystem;
  private final Path ideaConfigDir;

  public WorkspaceUpdater(ProjectFilesystem filesystem, Path ideaConfigDir) {
    this.filesystem = filesystem;
    this.ideaConfigDir = ideaConfigDir;
  }

  public Path getWorkspacePath() {
    return ideaConfigDir.resolve("workspace.xml");
  }

  public void updateOrCreateWorkspace() throws IOException {
    boolean workspaceUpdated = false;
    Document workspaceDocument = null;
    Path workspacePath = getWorkspacePath();
    if (filesystem.exists(workspacePath)) {
      try {
        LOG.debug("Trying to update existing workspace.");

        InputStream workspaceFile = filesystem.newFileInputStream(workspacePath);
        workspaceDocument = updateExistingWorkspace(workspaceFile);
        workspaceUpdated = true;
      } catch (ParserConfigurationException | SAXException | XPathExpressionException e) {
        LOG.error("Cannot update workspace.xml file, trying re-create it", e);
      }

      if (!workspaceUpdated && !filesystem.deleteFileAtPathIfExists(workspacePath)) {
        LOG.warn("Cannot remove file: %s", filesystem.resolve(workspacePath));
        return;
      }
    }

    if (!workspaceUpdated) {
      try {
        workspaceDocument = createNewWorkspace();
      } catch (ParserConfigurationException e) {
        LOG.error("Cannot create workspace.xml file", e);
        return;
      }
    }

    try {
      writeDocument(
          Preconditions.checkNotNull(workspaceDocument),
          filesystem.newFileOutputStream(workspacePath));
    } catch (TransformerException e) {
      LOG.error(e, "Cannot create workspace in %s", filesystem.resolve(workspacePath));
    }
  }

  private static Document updateExistingWorkspace(InputStream workspaceFile)
      throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
    Document workspaceDocument = parseWorkspaceFile(workspaceFile);

    removeIgnoredFoldersAndSetConvertedFlag(workspaceDocument);

    return workspaceDocument;
  }

  private static DocumentBuilder createDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    documentBuilderFactory.setNamespaceAware(true);
    return documentBuilderFactory.newDocumentBuilder();
  }

  private static Document parseWorkspaceFile(InputStream workspaceFile)
      throws ParserConfigurationException, IOException, SAXException {

    Document workspaceDocument = createDocumentBuilder().parse(workspaceFile);
    workspaceDocument.setXmlStandalone(true);
    return workspaceDocument;
  }

  private static void removeIgnoredFoldersAndSetConvertedFlag(Document workspaceDocument)
      throws XPathExpressionException {
    XPath xpath = XPathFactory.newInstance().newXPath();

    NodeList changeListManagerNodeList =
        findIgnoreNodesInChangeListManager(xpath, workspaceDocument);

    Node parentNode;

    if (changeListManagerNodeList.getLength() == 0) {
      parentNode = findChangeListManagerNode(xpath, workspaceDocument);
    } else {
      Node firstNode = changeListManagerNodeList.item(0);
      Node lastNode = changeListManagerNodeList.item(changeListManagerNodeList.getLength() - 1);
      parentNode = firstNode.getParentNode();

      removeNodeRange(parentNode, firstNode, lastNode);
    }

    if (parentNode == null) {
      Node projectNode = findProjectNode(xpath, workspaceDocument);
      if (projectNode == null) {
        projectNode = createNewProjectNode(workspaceDocument);
        workspaceDocument.appendChild(projectNode);
      }
      parentNode = createNewChangeListManagerNode(workspaceDocument);
      projectNode.appendChild(parentNode);
    }

    ensureExcludedConvertedToIgnoredOptionSetToTrue(workspaceDocument, xpath, parentNode);
  }

  private static NodeList findIgnoreNodesInChangeListManager(
      XPath xpath, Document workspaceDocument) throws XPathExpressionException {
    return (NodeList)
        xpath
            .compile("/project/component[@name = 'ChangeListManager']/ignored")
            .evaluate(workspaceDocument, XPathConstants.NODESET);
  }

  private static Node findChangeListManagerNode(XPath xpath, Document workspaceDocument)
      throws XPathExpressionException {
    return (Node)
        xpath
            .compile("/project/component[@name = 'ChangeListManager']")
            .evaluate(workspaceDocument, XPathConstants.NODE);
  }

  private static Node findProjectNode(XPath xpath, Document workspaceDocument)
      throws XPathExpressionException {
    return (Node) xpath.compile("/project").evaluate(workspaceDocument, XPathConstants.NODE);
  }

  private static void removeNodeRange(Node parentNode, Node firstNode, Node lastNode) {
    Node currentNode = firstNode;
    while (currentNode != lastNode) {
      Node nextNode = currentNode.getNextSibling();
      parentNode.removeChild(currentNode);
      currentNode = nextNode;
    }
    parentNode.removeChild(lastNode);
  }

  private static void ensureExcludedConvertedToIgnoredOptionSetToTrue(
      Document workspaceDocument, XPath xpath, Node parentNode) throws XPathExpressionException {
    String excludedConvertedToIgnoredLocation =
        "/project/component[@name = 'ChangeListManager']/"
            + "option[@name = 'EXCLUDED_CONVERTED_TO_IGNORED']";
    Node excludedConvertedToIgnoredOption =
        (Node)
            xpath
                .compile(excludedConvertedToIgnoredLocation)
                .evaluate(workspaceDocument, XPathConstants.NODE);

    if (excludedConvertedToIgnoredOption == null) {
      parentNode.appendChild(createNewOptionExcludedConvertedToIgnoredNode(workspaceDocument));
    } else {
      NamedNodeMap attributes = excludedConvertedToIgnoredOption.getAttributes();
      Node valueNode = attributes.getNamedItem("value");
      valueNode.setTextContent("true");
    }
  }

  private static Document createNewWorkspace() throws ParserConfigurationException {
    Document workspaceDocument = createNewWorkspaceDocument();
    Element project = addNewProjectNode(workspaceDocument);
    setExcludedFlag(workspaceDocument, project);
    return workspaceDocument;
  }

  private static Document createNewWorkspaceDocument() throws ParserConfigurationException {
    Document workspaceDocument = createDocumentBuilder().newDocument();
    workspaceDocument.setXmlStandalone(true);
    return workspaceDocument;
  }

  private static Element addNewProjectNode(Document workspaceDocument) {
    Element project = workspaceDocument.createElement("project");
    project.setAttribute("version", "4");
    workspaceDocument.appendChild(project);
    return project;
  }

  private static void setExcludedFlag(Document workspaceDocument, Element project) {
    Element changeListManager = workspaceDocument.createElement("component");
    changeListManager.setAttribute("name", "ChangeListManager");

    project.appendChild(changeListManager);

    changeListManager.appendChild(createNewOptionExcludedConvertedToIgnoredNode(workspaceDocument));
  }

  private static Element createNewProjectNode(Document workspaceDocument) {
    Element project = workspaceDocument.createElement("project");
    project.setAttribute("version", "4");
    return project;
  }

  private static Element createNewChangeListManagerNode(Document workspaceDocument) {
    Element component = workspaceDocument.createElement("component");
    component.setAttribute("name", "ChangeListManager");
    return component;
  }

  private static Element createNewOptionExcludedConvertedToIgnoredNode(Document workspaceDocument) {
    Element optionExcludedConvertedToIgnored = workspaceDocument.createElement("option");
    optionExcludedConvertedToIgnored.setAttribute("name", "EXCLUDED_CONVERTED_TO_IGNORED");
    optionExcludedConvertedToIgnored.setAttribute("value", "true");
    return optionExcludedConvertedToIgnored;
  }

  private static void writeDocument(Document workspaceDocument, OutputStream destination)
      throws TransformerException {
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

    transformer.transform(new DOMSource(workspaceDocument), new StreamResult(destination));
  }
}
