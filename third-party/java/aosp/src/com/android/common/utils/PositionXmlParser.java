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

package com.android.common.utils;

import static com.android.common.SdkConstants.UTF_8;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.common.ide.common.blame.SourcePosition;

import org.w3c.dom.Attr;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * A simple DOM XML parser which can retrieve exact beginning and end offsets
 * (and line and column numbers) for element nodes as well as attribute nodes.
 */
public class PositionXmlParser {
    private static final String UTF_16 = "UTF_16";               //$NON-NLS-1$
    private static final String UTF_16LE = "UTF_16LE";           //$NON-NLS-1$
    private static final String CONTENT_KEY = "contents";        //$NON-NLS-1$
    private static final String POS_KEY = "offsets";             //$NON-NLS-1$
    private static final String NAMESPACE_PREFIX_FEATURE =
            "http://xml.org/sax/features/namespace-prefixes";    //$NON-NLS-1$
    private static final String NAMESPACE_FEATURE =
            "http://xml.org/sax/features/namespaces";            //$NON-NLS-1$
    private static final String PROVIDE_XMLNS_URIS =
            "http://xml.org/sax/features/xmlns-uris";            //$NON-NLS-1$
    /** See http://www.w3.org/TR/REC-xml/#NT-EncodingDecl */
    private static final Pattern ENCODING_PATTERN =
            Pattern.compile("encoding=['\"](\\S*)['\"]");        //$NON-NLS-1$
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";; //$NON-NLS-1$

    /**
     * Parses the XML content from the given input stream.
     *
     * @param input the input stream containing the XML to be parsed
     * @param checkDtd whether or not download the DTD and validate it
     * @return the corresponding document
     * @throws ParserConfigurationException if a SAX parser is not available
     * @throws SAXException if the document contains a parsing error
     * @throws IOException if something is seriously wrong. This should not
     *             happen since the input source is known to be constructed from
     *             a string.
     */
    @NonNull
    public static Document parse(@NonNull InputStream input, boolean checkDtd)
            throws ParserConfigurationException, SAXException, IOException {
        // Read in all the data
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        while (true) {
          int r = input.read(buf);
          if (r == -1) {
            break;
          }
          out.write(buf, 0, r);
        }
        input.close();
        return parse(out.toByteArray(), checkDtd);
    }

    /**
     * @see PositionXmlParser#parse(InputStream, boolean)
     */
    @NonNull
    public static Document parse(@NonNull InputStream input)
            throws IOException, SAXException, ParserConfigurationException {
        return parse(input, true);
    }

    /**
     * @see PositionXmlParser#parse(byte[], boolean)
     */
    @NonNull
    public static Document parse(@NonNull byte[] data)
            throws ParserConfigurationException, SAXException, IOException {
        return parse(data, true);
    }

    /**
     * Parses the XML content from the given byte array
     *
     * @param data the raw XML data (with unknown encoding)
     * @param checkDtd whether or not download the DTD and validate it
     * @return the corresponding document
     * @throws ParserConfigurationException if a SAX parser is not available
     * @throws SAXException if the document contains a parsing error
     * @throws IOException if something is seriously wrong. This should not
     *             happen since the input source is known to be constructed from
     *             a string.
     */
    @NonNull
    public static Document parse(@NonNull byte[] data, boolean checkDtd)
      throws ParserConfigurationException, SAXException, IOException {
        String xml = getXmlString(data);
        xml = XmlUtils.stripBom(xml);
        return parse(xml, new InputSource(new StringReader(xml)), true, checkDtd);
    }

    /**
     * Parses the given XML content.
     *
     * @param xml the XML string to be parsed. This must be in the correct
     *     encoding already.
     * @return the corresponding document
     * @throws ParserConfigurationException if a SAX parser is not available
     * @throws SAXException if the document contains a parsing error
     * @throws IOException if something is seriously wrong. This should not
     *             happen since the input source is known to be constructed from
     *             a string.
     */
    @NonNull
    public static Document parse(@NonNull String xml)
            throws ParserConfigurationException, SAXException, IOException {
        xml = XmlUtils.stripBom(xml);
        return parse(xml, new InputSource(new StringReader(xml)), true, true);
    }

    @NonNull
    private static Document parse(@NonNull String xml, @NonNull InputSource input, boolean checkBom,
                                  boolean checkDtd)
            throws ParserConfigurationException, SAXException, IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            if (checkDtd) {
                factory.setFeature(NAMESPACE_FEATURE, true);
                factory.setFeature(NAMESPACE_PREFIX_FEATURE, true);
                factory.setFeature(PROVIDE_XMLNS_URIS, true);
            } else {
                factory.setFeature(LOAD_EXTERNAL_DTD, false);
            }
            SAXParser parser = factory.newSAXParser();
            DomBuilder handler = new DomBuilder(xml);
            XMLReader xmlReader = parser.getXMLReader();
            xmlReader.setProperty(
                    "http://xml.org/sax/properties/lexical-handler",
                    handler
            );
            parser.parse(input, handler);
            return handler.getDocument();
        } catch (SAXException e) {
            if (checkBom && e.getMessage().contains("Content is not allowed in prolog")) {
                // Byte order mark in the string? Skip it. There are many markers
                // (see http://en.wikipedia.org/wiki/Byte_order_mark) so here we'll
                // just skip those up to the XML prolog beginning character, <
                xml = xml.replaceFirst("^([\\W]+)<","<");  //$NON-NLS-1$ //$NON-NLS-2$
                return parse(xml, new InputSource(new StringReader(xml)), false, checkDtd);
            }
            throw e;
        }
    }

    /**
     * Returns the String corresponding to the given byte array of XML data
     * (with unknown encoding). This method attempts to guess the encoding based
     * on the XML prologue.
     * @param data the XML data to be decoded into a string
     * @return a string corresponding to the XML data
     */
    @NonNull
    public static String getXmlString(@NonNull byte[] data) {
        return getXmlString(data, UTF_8);
    }

    /**
     * Returns the String corresponding to the given byte array of XML data
     * (with unknown encoding). This method attempts to guess the encoding based
     * on the XML prologue.
     * @param data the XML data to be decoded into a string
     * @param defaultCharset the default charset to use if not specified by an encoding prologue
     *                       attribute or a byte order mark
     * @return a string corresponding to the XML data
     */
    @NonNull
    public static String getXmlString(@NonNull byte[] data, @NonNull String defaultCharset) {
        int offset = 0;

        String charset = null;
        // Look for the byte order mark, to see if we need to remove bytes from
        // the input stream (and to determine whether files are big endian or little endian) etc
        // for files which do not specify the encoding.
        // See http://unicode.org/faq/utf_bom.html#BOM for more.
        if (data.length > 4) {
            if (data[0] == (byte)0xef && data[1] == (byte)0xbb && data[2] == (byte)0xbf) {
                // UTF-8
                defaultCharset = charset = UTF_8;
                offset += 3;
            } else if (data[0] == (byte)0xfe && data[1] == (byte)0xff) {
                //  UTF-16, big-endian
                defaultCharset = charset = UTF_16;
                offset += 2;
            } else if (data[0] == (byte)0x0 && data[1] == (byte)0x0
                    && data[2] == (byte)0xfe && data[3] == (byte)0xff) {
                // UTF-32, big-endian
                defaultCharset = charset = "UTF_32";    //$NON-NLS-1$
                offset += 4;
            } else if (data[0] == (byte)0xff && data[1] == (byte)0xfe
                    && data[2] == (byte)0x0 && data[3] == (byte)0x0) {
                // UTF-32, little-endian. We must check for this *before* looking for
                // UTF_16LE since UTF_32LE has the same prefix!
                defaultCharset = charset = "UTF_32LE";  //$NON-NLS-1$
                offset += 4;
            } else if (data[0] == (byte)0xff && data[1] == (byte)0xfe) {
                //  UTF-16, little-endian
                defaultCharset = charset = UTF_16LE;
                offset += 2;
            }
        }
        int length = data.length - offset;

        // Guess encoding by searching for an encoding= entry in the first line.
        // The prologue, and the encoding names, will always be in ASCII - which means
        // we don't need to worry about strange character encodings for the prologue characters.
        // However, one wrinkle is that the whole file may be encoded in something like UTF-16
        // where there are two bytes per character, so we can't just look for
        //  ['e','n','c','o','d','i','n','g'] etc in the byte array since there could be
        // multiple bytes for each character. However, since again the prologue is in ASCII,
        // we can just drop the zeroes.
        boolean seenOddZero = false;
        boolean seenEvenZero = false;
        int prologueStart = -1;
        for (int lineEnd = offset; lineEnd < data.length; lineEnd++) {
            if (data[lineEnd] == 0) {
                if ((lineEnd - offset) % 2 == 0) {
                    seenEvenZero = true;
                } else {
                    seenOddZero = true;
                }
            } else if (data[lineEnd] == '\n' || data[lineEnd] == '\r') {
                break;
            } else if (data[lineEnd] == '<') {
                prologueStart = lineEnd;
            } else if (data[lineEnd] == '>') {
                // End of prologue. Quick check to see if this is a utf-8 file since that's
                // common
                for (int i = lineEnd - 4; i >= 0; i--) {
                    if ((data[i] == 'u' || data[i] == 'U')
                            && (data[i + 1] == 't' || data[i + 1] == 'T')
                            && (data[i + 2] == 'f' || data[i + 2] == 'F')
                            && (data[i + 3] == '-' || data[i + 3] == '_')
                            && (data[i + 4] == '8')
                            ) {
                        charset = UTF_8;
                        break;
                    }
                }

                if (charset == null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = prologueStart; i <= lineEnd; i++) {
                        if (data[i] != 0) {
                            sb.append((char) data[i]);
                        }
                    }
                    String prologue = sb.toString();
                    int encodingIndex = prologue.indexOf("encoding"); //$NON-NLS-1$
                    if (encodingIndex != -1) {
                        Matcher matcher = ENCODING_PATTERN.matcher(prologue);
                        if (matcher.find(encodingIndex)) {
                            charset = matcher.group(1);
                        }
                    }
                }

                break;
            }
        }

        // No prologue on the first line, and no byte order mark: Assume UTF-8/16
        if (charset == null) {
            charset = seenOddZero ? UTF_16LE : seenEvenZero ? UTF_16 : defaultCharset;
        }

        String xml = null;
        try {
            xml = new String(data, offset, length, charset);
        } catch (UnsupportedEncodingException e) {
            try {
                if (!charset.equals(defaultCharset)) {
                    xml = new String(data, offset, length, defaultCharset);
                }
            } catch (UnsupportedEncodingException u) {
                // Just use the default encoding below
            }
        }
        if (xml == null) {
            xml = new String(data, offset, length);
        }
        return xml;
    }

    /**
     * Returns the position for the given node. This is the start position. The
     * end position can be obtained via {@link Position#getEnd()}.
     *
     * @param node the node to look up position for
     * @return the position, or null if the node type is not supported for
     *         position info
     */
    @NonNull
    public static SourcePosition getPosition(@NonNull Node node) {
        return getPosition(node, -1, -1);
    }

    /**
     * Returns the position for the given node. This is the start position. The
     * end position can be obtained via {@link Position#getEnd()}. A specific
     * range within the node can be specified with the {@code start} and
     * {@code end} parameters.
     *
     * @param node the node to look up position for
     * @param start the relative offset within the node range to use as the
     *            starting position, inclusive, or -1 to not limit the range
     * @param end the relative offset within the node range to use as the ending
     *            position, or -1 to not limit the range
     * @return the position, or null if the node type is not supported for
     *         position info
     */

    @NonNull
    public static SourcePosition getPosition(@NonNull Node node, int start, int end) {
        Position p = getPositionHelper(node, start, end);
        return p == null ? SourcePosition.UNKNOWN : p.toSourcePosition();
    }

    /**
     * Finds the leaf node at the given offset
     *
     * @param document root node
     * @param offset   offset to look for
     * @return the leaf node at that offset, if any
     */
    @Nullable
    public static Node findNodeAtOffset(@NonNull Document document, int offset) {
        Element root = document.getDocumentElement();
        if (root != null) {
            return findNodeAtOffset(root, offset);
        }

        return null;
    }

    @Nullable
    private static Node findNodeAtOffset(@NonNull Node node, int offset) {
        Position p = getPositionHelper(node, -1, -1);
        if (p != null) {
            if (offset < p.getOffset()) {
                return null;
            }
            Position end = p.getEnd();
            if (end != null) {
                if (offset >= end.getOffset()) {
                    return null;
                }
            }
        } else {
            return null;
        }

        NodeList children = node.getChildNodes();
        for (int i = 0, n = children.getLength(); i < n; i++) {
            Node item = children.item(i);
            Node match = findNodeAtOffset(item, offset);
            if (match != null) {
                return match;
            }
        }

        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null) {
            for (int i = 0, n = attributes.getLength(); i < n; i++) {
                Node item = attributes.item(i);
                Node match = findNodeAtOffset(item, offset);
                if (match != null) {
                    return match;
                }
            }
        }

        return node;
    }

    /**
     * Finds the leaf node at the given offset
     *
     * @param document root node
     * @param line     the line
     * @param column   the column, or -1
     * @return the leaf node at that offset, if any
     */
    @Nullable
    public static Node findNodeAtLineAndCol(@NonNull Document document, int line, int column) {
        Element root = document.getDocumentElement();
        if (root != null) {
            return findNodeAtLineAndCol(root, line, column);
        }

        return null;
    }

    @Nullable
    private static Node findNodeAtLineAndCol(@NonNull Node node, int line, int column) {
        Position p = getPositionHelper(node, -1, -1);
        if (p != null) {
            if (line < p.getLine() || line == p.getLine() && column != -1
                    && column < p.getColumn()) {
                return null;
            }
            Position end = p.getEnd();
            if (end != null) {
                if (line > end.getLine() || line == end.getLine() && column != -1
                        && column >= end.getColumn()) {
                    return null;
                }
            }
        } else {
            return null;
        }

        NodeList children = node.getChildNodes();
        for (int i = 0, n = children.getLength(); i < n; i++) {
            Node item = children.item(i);
            Node match = findNodeAtLineAndCol(item, line, column);
            if (match != null) {
                return match;
            }
        }

        NamedNodeMap attributes = node.getAttributes();
        if (attributes != null) {
            for (int i = 0, n = attributes.getLength(); i < n; i++) {
                Node item = attributes.item(i);
                Node match = findNodeAtLineAndCol(item, line, column);
                if (match != null) {
                    return match;
                }
            }
        }

        return node;
    }

    @Nullable
    private static Position getPositionHelper(@NonNull Node node, int start, int end) {
        // Look up the position information stored while parsing for the given node.
        // Note however that we only store position information for elements (because
        // there is no SAX callback for individual attributes).
        // Therefore, this method special cases this:
        //  -- First, it looks at the owner element and uses its position
        //     information as a first approximation.
        //  -- Second, it uses that, as well as the original XML text, to search
        //     within the node range for an exact text match on the attribute name
        //     and if found uses that as the exact node offsets instead.
        if (node instanceof Attr) {
            Attr attr = (Attr) node;
            Position pos = (Position) attr.getOwnerElement().getUserData(POS_KEY);
            if (pos != null) {
                int startOffset = pos.getOffset();
                int endOffset = pos.getEnd().getOffset();
                if (start != -1) {
                    startOffset += start;
                    if (end != -1) {
                        endOffset = startOffset + (end - start);
                    }
                }

                // Find attribute in the text
                String contents = (String) node.getOwnerDocument().getUserData(CONTENT_KEY);
                if (contents == null) {
                    return null;
                }

                // Locate the name=value attribute in the source text
                // Fast string check first for the common occurrence
                String name = attr.getName();
                Pattern pattern = Pattern.compile(attr.getPrefix() != null
                    ? String.format("(%1$s\\s*=\\s*[\"'].*?[\"'])", name) //$NON-NLS-1$
                    : String.format("[^:](%1$s\\s*=\\s*[\"'].*?[\"'])", name));//$NON-NLS-1$
                Matcher matcher = pattern.matcher(contents);
                if (matcher.find(startOffset) && matcher.start(1) <= endOffset) {
                    int index = matcher.start(1);
                    // Adjust the line and column to this new offset
                    int line = pos.getLine();
                    int column = pos.getColumn();
                    for (int offset = pos.getOffset(); offset < index; offset++) {
                        char t = contents.charAt(offset);
                        if (t == '\n') {
                            line++;
                            column = 0;
                        } else {
                            column++;
                        }
                    }

                    Position attributePosition = new Position(line, column, index);
                    // Also set end range for retrieval in getLocation
                    attributePosition.setEnd(
                            new Position(line, column + matcher.end(1) - index, matcher.end(1)));
                    return attributePosition;
                } else {
                    // No regexp match either: just fall back to element position
                    return pos;
                }
            }
        } else if (node instanceof Text) {
            // Position of parent element, if any
            Position pos = null;
            if (node.getPreviousSibling() != null) {
                pos = (Position) node.getPreviousSibling().getUserData(POS_KEY);
            }
            if (pos == null) {
                pos = (Position) node.getParentNode().getUserData(POS_KEY);
            }
            if (pos != null) {
                // Attempt to point forward to the actual text node
                int startOffset = pos.getOffset();
                int endOffset = pos.getEnd().getOffset();
                int line = pos.getLine();
                int column = pos.getColumn();

                // Find attribute in the text
                String contents = (String) node.getOwnerDocument().getUserData(CONTENT_KEY);
                if (contents == null || contents.length() < endOffset) {
                    return null;
                }

                boolean inAttribute = false;
                for (int offset = startOffset; offset <= endOffset; offset++) {
                    char c = contents.charAt(offset);
                    if (c == '>' && !inAttribute) {
                        // Found the end of the element open tag: this is where the
                        // text begins.

                        // Skip >
                        offset++;
                        column++;

                        String text = node.getNodeValue();
                        int textIndex = 0;
                        int textLength = text.length();
                        int newLine = line;
                        int newColumn = column;
                        if (start != -1) {
                            textLength = Math.min(textLength, start);
                            for (; textIndex < textLength; textIndex++) {
                                char t = text.charAt(textIndex);
                                if (t == '\n') {
                                    newLine++;
                                    newColumn = 0;
                                } else {
                                    newColumn++;
                                }
                            }
                        } else {
                            // Skip text whitespace prefix, if the text node contains
                            // non-whitespace characters
                            for (; textIndex < textLength; textIndex++) {
                                char t = text.charAt(textIndex);
                                if (t == '\n') {
                                    newLine++;
                                    newColumn = 0;
                                } else if (!Character.isWhitespace(t)) {
                                    break;
                                } else {
                                    newColumn++;
                                }
                            }
                        }
                        if (textIndex == text.length()) {
                            textIndex = 0; // Whitespace node
                        } else {
                            line = newLine;
                            column = newColumn;
                        }

                        Position attributePosition = new Position(line, column, offset + textIndex);
                        // Also set end range for retrieval in getLocation
                        if (end != -1) {
                            attributePosition.setEnd(new Position(line, column, offset + end));
                        } else {
                            attributePosition.setEnd(
                                    new Position(line, column, offset + textLength));
                        }
                        return attributePosition;
                    } else if (c == '"') {
                        inAttribute = !inAttribute;
                    } else if (c == '\n') {
                        line++;
                        column = -1; // pre-subtract column added below
                    }
                    column++;
                }

                return pos;
            }
        }

        return (Position) node.getUserData(POS_KEY);
    }

    /**
     * SAX parser handler which incrementally builds up a DOM document as we go
     * along, and updates position information along the way. Position
     * information is attached to the DOM nodes by setting user data with the
     * {@link #POS_KEY} key.
     */
    private static final class DomBuilder extends DefaultHandler2 {
        private final String mXml;
        private final Document mDocument;
        private Locator mLocator;
        private int mCurrentLine = 0;
        private int mCurrentOffset;
        private int mCurrentColumn;
        private final List<Element> mStack = new ArrayList<Element>();
        private final StringBuilder mPendingText = new StringBuilder();

        private DomBuilder(String xml) throws ParserConfigurationException {
            mXml = xml;

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setValidating(false);
            DocumentBuilder docBuilder = factory.newDocumentBuilder();
            mDocument = docBuilder.newDocument();
            mDocument.setUserData(CONTENT_KEY, xml, null);
        }

        /** Returns the document parsed by the handler */
        @NonNull
        Document getDocument() {
            return mDocument;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.mLocator = locator;
        }

        @Override
        public void startElement(String uri, String localName, String qName,
                Attributes attributes) throws SAXException {
            try {
                flushText();
                Element element = mDocument.createElementNS(uri, qName);
                for (int i = 0; i < attributes.getLength(); i++) {
                    if (attributes.getURI(i) != null && !attributes.getURI(i).isEmpty()) {
                        Attr attr = mDocument.createAttributeNS(attributes.getURI(i),
                                attributes.getQName(i));
                        attr.setValue(attributes.getValue(i));
                        element.setAttributeNodeNS(attr);
                        assert attr.getOwnerElement() == element;
                    } else {
                        Attr attr = mDocument.createAttribute(attributes.getQName(i));
                        attr.setValue(attributes.getValue(i));
                        element.setAttributeNode(attr);
                        assert attr.getOwnerElement() == element;
                    }
                }

                Position pos = getCurrentPosition();

                // The starting position reported to us by SAX is really the END of the
                // open tag in an element, when all the attributes have been processed.
                // We have to scan backwards to find the real beginning. We'll do that
                // by scanning backwards.
                // -1: Make sure that when we have <foo></foo> we don't consider </foo>
                // the beginning since pos.offset will typically point to the first character
                // AFTER the element open tag, which could be a closing tag or a child open
                // tag
                element.setUserData(POS_KEY, findOpeningTag(pos), null);
                mStack.add(element);
            } catch (Exception t) {
                throw new SAXException(t);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            flushText();
            Element element = mStack.remove(mStack.size() - 1);

            Position pos = (Position) element.getUserData(POS_KEY);
            assert pos != null;
            pos.setEnd(getCurrentPosition());

            addNodeToParent(element);
        }

        @Override
        public void comment(char[] chars, int start, int length) throws SAXException {

            flushText();
            String comment = new String(chars, start, length);
            Comment domComment = mDocument.createComment(comment);

            // current position is the closing comment tag.
            Position currentPosition = getCurrentPosition();
            Position startPosition = findOpeningTag(currentPosition);
            startPosition.setEnd(currentPosition);

            domComment.setUserData(POS_KEY, startPosition, null);
            addNodeToParent(domComment);
        }

        /**
         * Adds a node to the current parent element being visited, or to the document if there is
         * no parent in context.
         * @param nodeToAdd xml node to add.
         */
        private void addNodeToParent(Node nodeToAdd) {
            if (mStack.isEmpty()){
                mDocument.appendChild(nodeToAdd);
            } else {
                Element parent = mStack.get(mStack.size() - 1);
                parent.appendChild(nodeToAdd);
            }
        }

        /**
         * Find opening tags from the current position.
         * < cannot appear in attribute values or anywhere else within
         * an element open tag, so we know the first occurrence is the real
         * element start
         * For comments, it is not legal to put < in a comment, however we are not
         * validating so we will return an invalid column in that case.
         * @param startingPosition the position to walk backwards until < is reached.
         * @return the opening tag position or startPosition if cannot be found.
         */
        private Position findOpeningTag(Position startingPosition) {
            for (int offset = startingPosition.getOffset() - 1; offset >= 0; offset--) {
                char c = mXml.charAt(offset);

                if (c == '<') {
                    // Adjust line position
                    int line = startingPosition.getLine();
                    for (int i = offset, n = startingPosition.getOffset(); i < n; i++) {
                        if (mXml.charAt(i) == '\n') {
                            line--;
                        }
                    }

                    // Compute new column position
                    int column = 0;
                    for (int i = offset - 1; i >= 0; i--, column++) {
                        if (mXml.charAt(i) == '\n') {
                            break;
                        }
                    }

                    return new Position(line, column, offset);
                }
            }
            // we did not find it, approximate.
            return startingPosition;
        }

            /**
         * Returns a position holder for the current position. The most
         * important part of this function is to incrementally compute the
         * offset as well, by counting forwards until it reaches the new line
         * number and column position of the XML parser, counting characters as
         * it goes along.
         */
        private Position getCurrentPosition() {
            int line = mLocator.getLineNumber() - 1;
            int column = mLocator.getColumnNumber() - 1;

            // Compute offset incrementally now that we have the new line and column
            // numbers
            int xmlLength = mXml.length();
            while (mCurrentLine < line && mCurrentOffset < xmlLength) {
                char c = mXml.charAt(mCurrentOffset);
                if (c == '\r' && mCurrentOffset < xmlLength - 1) {
                    if (mXml.charAt(mCurrentOffset + 1) != '\n') {
                        mCurrentLine++;
                        mCurrentColumn = 0;
                    }
                } else if (c == '\n') {
                    mCurrentLine++;
                    mCurrentColumn = 0;
                } else {
                    mCurrentColumn++;
                }
                mCurrentOffset++;
            }

            mCurrentOffset += column - mCurrentColumn;
            if (mCurrentOffset >= xmlLength) {
                // The parser sometimes passes wrong column numbers at the
                // end of the file: Ensure that the offset remains valid.
                mCurrentOffset = xmlLength;
            }
            mCurrentColumn = column;

            return new Position(mCurrentLine, mCurrentColumn, mCurrentOffset);
        }

        @Override
        public void characters(char c[], int start, int length) throws SAXException {
            mPendingText.append(c, start, length);
        }

        private void flushText() {
            if (mPendingText.length() > 0 && !mStack.isEmpty()) {
                Element element = mStack.get(mStack.size() - 1);
                Node textNode = mDocument.createTextNode(mPendingText.toString());
                element.appendChild(textNode);
                mPendingText.setLength(0);
            }
        }
    }

    private static class Position {
        /** The line number (0-based where the first line is line 0) */
        private final int mLine;
        private final int mColumn;
        private final int mOffset;
        private Position mEnd;

        /**
         * Creates a new {@link Position}
         *
         * @param line the 0-based line number, or -1 if unknown
         * @param column the 0-based column number, or -1 if unknown
         * @param offset the offset, or -1 if unknown
         */
        public Position(int line, int column, int offset) {
            this.mLine = line;
            this.mColumn = column;
            this.mOffset = offset;
        }

        public int getLine() {
            return mLine;
        }

        public int getOffset() {
            return mOffset;
        }

        public int getColumn() {
            return mColumn;
        }

        public Position getEnd() {
            return mEnd;
        }

        public void setEnd(@NonNull Position end) {
            mEnd = end;
        }

        public SourcePosition toSourcePosition() {
           int endLine = mLine, endColumn = mColumn, endOffset = mOffset;

            if (mEnd != null) {
                endLine = mEnd.getLine();
                endColumn = mEnd.getColumn();
                endOffset = mEnd.getOffset();
            }

            return new SourcePosition(mLine, mColumn, mOffset, endLine, endColumn, endOffset);
        }
    }

    private PositionXmlParser() { }
}
