/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.manifmerger;

import com.android.common.annotations.NonNull;
import com.android.common.annotations.Nullable;
import com.android.manifmerger.IMergerLog.FileAndLine;
import com.android.manifmerger.IMergerLog.Severity;
import com.android.common.utils.ILogger;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

/**
 * A few XML handling utilities.
 */
public class XmlUtils {

    private static final String DATA_ORIGIN_FILE = "manif.merger.file";         //$NON-NLS-1$
    private static final String DATA_FILE_NAME   = "manif.merger.filename";     //$NON-NLS-1$
    private static final String DATA_LINE_NUMBER = "manif.merger.line#";        //$NON-NLS-1$

    /**
     * Parses the given XML file as a DOM document.
     * The parser does not validate the DTD nor any kind of schema.
     * It is namespace aware.
     * <p/>
     * This adds a user tag with the original {@link java.io.File} to the returned document.
     * You can retrieve this file later by using {@link #extractXmlFilename(org.w3c.dom.Node)}.
     *
     * @param xmlFile The XML {@link java.io.File} to parse. Must not be null.
     * @param log An {@link ILogger} for reporting errors. Must not be null.
     * @return A new DOM {@link org.w3c.dom.Document}, or null.
     */
    @Nullable
    static Document parseDocument(@NonNull final File xmlFile, @NonNull final IMergerLog log) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            InputSource is = new InputSource(new FileReader(xmlFile));
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            DocumentBuilder builder = factory.newDocumentBuilder();

            // We don't want the default handler which prints errors to stderr.
            builder.setErrorHandler(new ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                    log.error(Severity.WARNING,
                            new FileAndLine(xmlFile.getAbsolutePath(), 0),
                            "Warning when parsing: %1$s",
                            e.toString());
                }
                @Override
                public void fatalError(SAXParseException e) {
                    log.error(Severity.ERROR,
                            new FileAndLine(xmlFile.getAbsolutePath(), 0),
                            "Fatal error when parsing: %1$s",
                            xmlFile.getName(), e.toString());
                }
                @Override
                public void error(SAXParseException e) {
                    log.error(Severity.ERROR,
                            new FileAndLine(xmlFile.getAbsolutePath(), 0),
                            "Error when parsing: %1$s",
                            e.toString());
                }
            });

            Document doc = builder.parse(is);
            doc.setUserData(DATA_ORIGIN_FILE, xmlFile, null /*handler*/);
            findLineNumbers(doc, 1);

            return doc;

        } catch (FileNotFoundException e) {
            log.error(Severity.ERROR,
                    new FileAndLine(xmlFile.getAbsolutePath(), 0),
                    "XML file not found");

        } catch (Exception e) {
            log.error(Severity.ERROR,
                    new FileAndLine(xmlFile.getAbsolutePath(), 0),
                    "Failed to parse XML file: %1$s",
                    e.toString());
        }

        return null;
    }

    /**
     * Parses the given XML string as a DOM document.
     * The parser does not validate the DTD nor any kind of schema.
     * It is namespace aware.
     *
     * @param xml The XML string to parse. Must not be null.
     * @param log An {@link ILogger} for reporting errors. Must not be null.
     * @return A new DOM {@link org.w3c.dom.Document}, or null.
     */
    @Nullable
    static Document parseDocument(@NonNull String xml,
            @NonNull IMergerLog log,
            @NonNull FileAndLine errorContext) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            InputSource is = new InputSource(new StringReader(xml));
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(is);
            findLineNumbers(doc, 1);
            return doc;
        } catch (Exception e) {
            log.error(Severity.ERROR, errorContext, "Failed to parse XML string");
        }

        return null;
    }

    /**
     * Decorates the document with the specified file name, which can be
     * retrieved later by calling {@link #extractLineNumber(org.w3c.dom.Node)}.
     * <p/>
     * It also tries to add line number information, with the caveat that the
     * current implementation is a gross approximation.
     * <p/>
     * There is no need to call this after calling one of the {@code parseDocument()}
     * methods since they already decorated their own document.
     *
     * @param doc The document to decorate.
     * @param fileName The name to retrieve later for that document.
     */
    static void decorateDocument(@NonNull Document doc, @NonNull String fileName) {
        doc.setUserData(DATA_FILE_NAME, fileName, null /*handler*/);
        findLineNumbers(doc, 1);
    }

    /**
     * Extracts the origin {@link java.io.File} that {@link #parseDocument(java.io.File, com.android.manifmerger.IMergerLog)}
     * added to the XML document or the string added by
     *
     * @param xmlNode Any node from a document returned by {@link #parseDocument(java.io.File, com.android.manifmerger.IMergerLog)}.
     * @return The {@link java.io.File} object used to create the document or null.
     */
    @Nullable
    static String extractXmlFilename(@Nullable Node xmlNode) {
        if (xmlNode != null && xmlNode.getNodeType() != Node.DOCUMENT_NODE) {
            xmlNode = xmlNode.getOwnerDocument();
        }
        if (xmlNode != null) {
            Object data = xmlNode.getUserData(DATA_ORIGIN_FILE);
            if (data instanceof File) {
                return ((File) data).getName();
            }
            data = xmlNode.getUserData(DATA_FILE_NAME);
            if (data instanceof String) {
                return (String) data;
            }
        }

        return null;
    }

    /**
     * Extracts the origin {@link java.io.File} that {@link #parseDocument(java.io.File, com.android.manifmerger.IMergerLog)}
     * added to the XML document or the string added by
     *
     * @param xmlNode Any node from a document returned by {@link #parseDocument(java.io.File, com.android.manifmerger.IMergerLog)}.
     * @return The {@link java.io.File} object used to create the document or null.
     */
    @Nullable
    static String extractXmlAbsoluteFilename(@Nullable Node xmlNode) {
        if (xmlNode != null && xmlNode.getNodeType() != Node.DOCUMENT_NODE) {
            xmlNode = xmlNode.getOwnerDocument();
        }
        if (xmlNode != null) {
            Object data = xmlNode.getUserData(DATA_ORIGIN_FILE);
            if (data instanceof File) {
                return ((File) data).getAbsolutePath();
            }
            data = xmlNode.getUserData(DATA_FILE_NAME);
            if (data instanceof String) {
                return (String) data;
            }
        }

        return null;
    }

    /**
     * This is a CRUDE INEXACT HACK to decorate the DOM with some kind of line number
     * information for elements. It's inexact because by the time we get the DOM we
     * already have lost all the information about whitespace between attributes.
     * <p/>
     * Also we don't even try to deal with \n vs \r vs \r\n insanity. This only counts
     * the \n occurring in text nodes to determine line advances, which is clearly flawed.
     * <p/>
     * However it's good enough for testing, and we'll replace it by a PositionXmlParser
     * once it's moved into com.android.util.
     */
    private static int findLineNumbers(Node node, int line) {
        for (; node != null; node = node.getNextSibling()) {
            node.setUserData(DATA_LINE_NUMBER, Integer.valueOf(line), null /*handler*/);

            if (node.getNodeType() == Node.TEXT_NODE) {
                String text = node.getNodeValue();
                if (text.length() > 0) {
                    for (int pos = 0; (pos = text.indexOf('\n', pos)) != -1; pos++) {
                        ++line;
                    }
                }
            }

            Node child = node.getFirstChild();
            if (child != null) {
                line = findLineNumbers(child, line);
            }
        }
        return line;
    }

    /**
     * Extracts the line number that {@link #findLineNumbers} added to the XML nodes.
     *
     * @param xmlNode Any node from a document returned by {@link #parseDocument(java.io.File, com.android.manifmerger.IMergerLog)}.
     * @return The line number if found or 0.
     */
    static int extractLineNumber(@Nullable Node xmlNode) {
        if (xmlNode != null) {
            Object data = xmlNode.getUserData(DATA_LINE_NUMBER);
            if (data instanceof Integer) {
                return ((Integer) data).intValue();
            }
        }

        return 0;
    }

    /**
     * Find the prefix for the given NS_URI in the document.
     *
     * @param doc The document root.
     * @param nsUri The Namespace URI to look for.
     * @return The namespace prefix if found or null.
     */
    static String lookupNsPrefix(Document doc, String nsUri) {
        return com.android.common.utils.XmlUtils.lookupNamespacePrefix(doc, nsUri);
    }

    /**
     * Outputs the given XML {@link org.w3c.dom.Document} to the file {@code outFile}.
     *
     * TODO right now reformats the document. Needs to output as-is, respecting white-space.
     *
     * @param doc The document to output. Must not be null.
     * @param outFile The {@link java.io.File} where to write the document.
     * @param log A log in case of error.
     * @return True if the file was written, false in case of error.
     */
    static boolean printXmlFile(
            @NonNull Document doc,
            @NonNull File outFile,
            @NonNull IMergerLog log) {
        // Quick thing based on comments from http://stackoverflow.com/questions/139076
        try {
            Transformer tf = TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");         //$NON-NLS-1$
            tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");                   //$NON-NLS-1$
            tf.setOutputProperty(OutputKeys.INDENT, "yes");                       //$NON-NLS-1$
            tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount",     //$NON-NLS-1$
                                 "4");                                            //$NON-NLS-1$
            tf.transform(new DOMSource(doc), new StreamResult(outFile));
            return true;
        } catch (TransformerException e) {
            log.error(Severity.ERROR,
                    new FileAndLine(outFile.getName(), 0),
                    "Failed to write XML file: %1$s",
                    e.toString());
            return false;
        }
    }

    /**
     * Outputs the given XML {@link org.w3c.dom.Document} as a string.
     *
     * TODO right now reformats the document. Needs to output as-is, respecting white-space.
     *
     * @param doc The document to output. Must not be null.
     * @param log A log in case of error.
     * @return A string representation of the XML. Null in case of error.
     */
    static String printXmlString(
            @NonNull Document doc,
            @NonNull IMergerLog log) {
        try {
            Transformer tf = TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");        //$NON-NLS-1$
            tf.setOutputProperty(OutputKeys.ENCODING, "UTF-8");                  //$NON-NLS-1$
            tf.setOutputProperty(OutputKeys.INDENT, "yes");                      //$NON-NLS-1$
            tf.setOutputProperty("{http://xml.apache.org/xslt}indent-amount",    //$NON-NLS-1$
                                 "4");                                           //$NON-NLS-1$
            StringWriter sw = new StringWriter();
            tf.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();
        } catch (TransformerException e) {
            log.error(Severity.ERROR,
                    new FileAndLine(extractXmlFilename(doc), 0),
                    "Failed to write XML file: %1$s",
                    e.toString());
            return null;
        }
    }

    /**
     * Dumps the structure of the DOM to a simple text string.
     *
     * @param node The first node to dump (recursively). Can be null.
     * @param nextSiblings If true, will also dump the following siblings.
     *   If false, it will just process the given node.
     * @return A string representation of the Node structure, useful for debugging.
     */
    @NonNull
    static String dump(@Nullable Node node, boolean nextSiblings) {
        return dump(node, 0 /*offset*/, nextSiblings, true /*deep*/, null /*keyAttr*/);
    }


    /**
     * Dumps the structure of the DOM to a simple text string.
     * Each line is terminated with a \n separator.
     *
     * @param node The first node to dump. Can be null.
     * @param offsetIndex The offset to add at the begining of each line. Each offset is
     *   converted into 2 space characters.
     * @param nextSiblings If true, will also dump the following siblings.
     *   If false, it will just process the given node.
     * @param deep If true, this will recurse into children.
     * @param keyAttr An optional attribute *local* name to insert when writing an element.
     *   For example when writing an Activity, it helps to always insert "name" attribute.
     * @return A string representation of the Node structure, useful for debugging.
     */
    @NonNull
    static String dump(
            @Nullable Node node,
            int offsetIndex,
            boolean nextSiblings,
            boolean deep,
            @Nullable String keyAttr) {
        StringBuilder sb = new StringBuilder();

        String offset = "";                 //$NON-NLS-1$
        for (int i = 0; i < offsetIndex; i++) {
            offset += "  ";                 //$NON-NLS-1$
        }

        if (node == null) {
            sb.append(offset).append("(end reached)\n");

        } else {
            for (; node != null; node = node.getNextSibling()) {
                String type = null;
                short t = node.getNodeType();
                switch(t) {
                case Node.ELEMENT_NODE:
                    String attr = "";
                    if (keyAttr != null) {
                        NamedNodeMap attrs = node.getAttributes();
                        if (attrs != null) {
                            for (int i = 0; i < attrs.getLength(); i++) {
                                Node a = attrs.item(i);
                                if (a != null && keyAttr.equals(a.getLocalName())) {
                                    attr = String.format(" %1$s=%2$s",
                                            a.getNodeName(), a.getNodeValue());
                                    break;
                                }
                            }
                        }
                    }
                    sb.append(String.format("%1$s<%2$s%3$s>\n",
                            offset, node.getNodeName(), attr));
                    break;
                case Node.COMMENT_NODE:
                    sb.append(String.format("%1$s<!-- %2$s -->\n",
                            offset, node.getNodeValue()));
                    break;
                case Node.TEXT_NODE:
                        String txt = node.getNodeValue().trim();
                         if (txt.length() == 0) {
                             // Keep this for debugging. TODO make it a flag
                             // to dump whitespace on debugging. Otherwise ignore it.
                             // txt = "[whitespace]";
                             break;
                         }
                        sb.append(String.format("%1$s%2$s\n", offset, txt));
                    break;
                case Node.ATTRIBUTE_NODE:
                    sb.append(String.format("%1$s    @%2$s = %3$s\n",
                            offset, node.getNodeName(), node.getNodeValue()));
                    break;
                case Node.CDATA_SECTION_NODE:
                    type = "cdata";                 //$NON-NLS-1$
                    break;
                case Node.DOCUMENT_NODE:
                    type = "document";              //$NON-NLS-1$
                    break;
                case Node.PROCESSING_INSTRUCTION_NODE:
                    type = "PI";                    //$NON-NLS-1$
                    break;
                default:
                    type = Integer.toString(t);
                }

                if (type != null) {
                    sb.append(String.format("%1$s[%2$s] <%3$s> %4$s\n",
                            offset, type, node.getNodeName(), node.getNodeValue()));
                }

                if (deep) {
                    List<Attr> attrs = sortedAttributeList(node.getAttributes());
                    for (Attr attr : attrs) {
                        sb.append(String.format("%1$s    @%2$s = %3$s\n",
                                offset, attr.getNodeName(), attr.getNodeValue()));
                    }

                    Node child = node.getFirstChild();
                    if (child != null) {
                        sb.append(dump(child, offsetIndex+1, true, true, keyAttr));
                    }
                }

                if (!nextSiblings) {
                    break;
                }
            }
        }
        return sb.toString();
    }

    /**
     * Returns a sorted list of attributes.
     * The list is never null and does not contain null items.
     *
     * @param attrMap A Node map as returned by {@link org.w3c.dom.Node#getAttributes()}.
     *   Can be null, in which case an empty list is returned.
     * @return A non-null, possible empty, list of all nodes that are actual {@link org.w3c.dom.Attr},
     *   sorted by increasing attribute name.
     */
    @NonNull
    public static List<Attr> sortedAttributeList(@Nullable NamedNodeMap attrMap) {
        List<Attr> list = new ArrayList<Attr>();

        if (attrMap != null) {
            for (int i = 0; i < attrMap.getLength(); i++) {
                Node attr = attrMap.item(i);
                if (attr instanceof Attr) {
                    list.add((Attr) attr);
                }
            }
        }

        if (list.size() > 1) {
            // Sort it by attribute name
            Collections.sort(list, getAttrComparator());
        }

        return list;
    }

    /**
     * Returns a comparator for {@link org.w3c.dom.Attr}, alphabetically sorted by name.
     * The "name" attribute is special and always sorted to the front.
     */
    @NonNull
    public static Comparator<? super Attr> getAttrComparator() {
        return new Comparator<Attr>() {
            @Override
            public int compare(Attr a1, Attr a2) {
                String s1 = a1 == null ? "" : a1.getNodeName();           //$NON-NLS-1$
                String s2 = a2 == null ? "" : a2.getNodeName();           //$NON-NLS-1$

                int prio1 = s1.equals("name") ? 0 : 1;                    //$NON-NLS-1$
                int prio2 = s2.equals("name") ? 0 : 1;                    //$NON-NLS-1$
                if (prio1 == 0 || prio2 == 0) {
                    return prio1 - prio2;
                }

                return s1.compareTo(s2);
            }
        };
    }
}
