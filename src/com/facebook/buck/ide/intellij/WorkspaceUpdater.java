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

package com.facebook.buck.ide.intellij;

import com.facebook.buck.log.Logger;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableSortedSet;
import java.io.File;
import java.io.IOException;
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

  private final File workspaceFile;

  public WorkspaceUpdater(Path projectIdeaConfigPath) {
    workspaceFile = projectIdeaConfigPath.resolve("workspace.xml").toFile();
  }

  public File getWorkspaceFile() {
    return workspaceFile;
  }

  public void updateOrCreateWorkspace(ImmutableSortedSet<String> excludedPaths) throws IOException {
    boolean workspaceUpdated = false;
    Document workspaceDocument = null;
    if (workspaceFile.exists()) {
      try {
        LOG.debug("Trying to update existing workspace.");
        workspaceDocument = updateExistingWorkspace(workspaceFile, excludedPaths);
        workspaceUpdated = true;
      } catch (ParserConfigurationException | SAXException | XPathExpressionException e) {
        LOG.error("Cannot update workspace.xml file, trying re-create it", e);
      }

      if (!workspaceUpdated && !workspaceFile.delete()) {
        LOG.warn("Cannot remove file: %s", workspaceFile.getAbsolutePath());
        return;
      }
    }

    if (!workspaceUpdated) {
      try {
        workspaceDocument = createNewWorkspace(excludedPaths);
      } catch (ParserConfigurationException e) {
        LOG.error("Cannot create workspace.xml file", e);
        return;
      }
    }

    try {
      writeDocument(Preconditions.checkNotNull(workspaceDocument), workspaceFile);
    } catch (TransformerException e) {
      LOG.error(e, "Cannot create workspace in %s", workspaceFile);
    }
  }

  private static Document updateExistingWorkspace(
      File workspaceFile, ImmutableSortedSet<String> excludedPaths)
      throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
    Document workspaceDocument = parseWorkspaceFile(workspaceFile);

    updateIgnoredFolders(workspaceDocument, excludedPaths);

    return workspaceDocument;
  }

  private static DocumentBuilder createDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    documentBuilderFactory.setNamespaceAware(true);
    return documentBuilderFactory.newDocumentBuilder();
  }

  private static Document parseWorkspaceFile(File workspaceFile)
      throws ParserConfigurationException, IOException, SAXException {
    Document workspaceDocument = createDocumentBuilder().parse(workspaceFile);
    workspaceDocument.setXmlStandalone(true);
    return workspaceDocument;
  }

  private static void updateIgnoredFolders(
      Document workspaceDocument, ImmutableSortedSet<String> excludedPaths)
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

    addNewNodes(workspaceDocument, parentNode, excludedPaths);

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

  private static void addNewNodes(
      Document workspaceDocument, Node parentNode, ImmutableCollection<String> excludedPaths) {
    excludedPaths.forEach(
        excludeFolder ->
            parentNode.appendChild(createNewIgnoreNode(workspaceDocument, excludeFolder)));
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

  private static Document createNewWorkspace(ImmutableSortedSet<String> excludedPaths)
      throws ParserConfigurationException {
    Document workspaceDocument = createNewWorkspaceDocument();
    Element project = addNewProjectNode(workspaceDocument);
    addIgnoredFolders(workspaceDocument, project, excludedPaths);
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

  private static void addIgnoredFolders(
      Document workspaceDocument, Element project, ImmutableSortedSet<String> excludedPaths) {
    Element changeListManager = workspaceDocument.createElement("component");
    changeListManager.setAttribute("name", "ChangeListManager");

    project.appendChild(changeListManager);

    excludedPaths.forEach(
        excludeFolder ->
            changeListManager.appendChild(createNewIgnoreNode(workspaceDocument, excludeFolder)));

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

  private static Element createNewIgnoreNode(Document workspaceDocument, String excludeFolder) {
    Element ignored = workspaceDocument.createElement("ignored");
    ignored.setAttribute("path", String.format("$PROJECT_DIR$/%s/", excludeFolder));
    return ignored;
  }

  private static Element createNewOptionExcludedConvertedToIgnoredNode(Document workspaceDocument) {
    Element optionExcludedConvertedToIgnored = workspaceDocument.createElement("option");
    optionExcludedConvertedToIgnored.setAttribute("name", "EXCLUDED_CONVERTED_TO_IGNORED");
    optionExcludedConvertedToIgnored.setAttribute("value", "true");
    return optionExcludedConvertedToIgnored;
  }

  private static void writeDocument(Document workspaceDocument, File destination)
      throws TransformerException {
    Transformer transformer = TransformerFactory.newInstance().newTransformer();
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty(OutputKeys.METHOD, "xml");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

    transformer.transform(new DOMSource(workspaceDocument), new StreamResult(destination));
  }
}
