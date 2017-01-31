/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.common.ide.common.xml;

import com.android.common.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.common.resources.ResourceFolderType;
import com.android.common.utils.SdkUtils;
import com.android.common.utils.XmlUtils;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.android.common.SdkConstants.DOT_XML;
import static com.android.common.SdkConstants.TAG_COLOR;
import static com.android.common.SdkConstants.TAG_DIMEN;
import static com.android.common.SdkConstants.TAG_ITEM;
import static com.android.common.SdkConstants.TAG_STRING;
import static com.android.common.SdkConstants.TAG_STYLE;
import static com.android.common.SdkConstants.XMLNS;
import static com.android.common.utils.XmlUtils.XML_COMMENT_BEGIN;
import static com.android.common.utils.XmlUtils.XML_COMMENT_END;
import static com.android.common.utils.XmlUtils.XML_PROLOG;

/**
 * Visitor which walks over the subtree of the DOM to be formatted and pretty prints
 * the DOM into the given {@link StringBuilder}
 */
public class XmlPrettyPrinter {

    /** The style to print the XML in */
    private final XmlFormatStyle mStyle;

    /** Formatting preferences to use when formatting the XML */
    private final XmlFormatPreferences mPrefs;
    /** Start node to start formatting at */
    private Node mStartNode;
    /** Start node to stop formatting after */
    private Node mEndNode;
    /** Whether the visitor is currently in range */
    private boolean mInRange;
    /** Output builder */
    @SuppressWarnings("StringBufferField")
    private StringBuilder mOut;
    /** String to insert for a single indentation level */
    private String mIndentString;
    /** Line separator to use */
    private String mLineSeparator;
    /** If true, we're only formatting an open tag */
    private boolean mOpenTagOnly;
    /** List of indentation to use for each given depth */
    private String[] mIndentationLevels;
    /** Whether the formatter should end the document with a newline */
    private boolean mEndWithNewline;

    /**
     * Creates a new {@link XmlPrettyPrinter}
     *
     * @param prefs the preferences to format with
     * @param style the style to format with
     * @param lineSeparator the line separator to use, such as "\n" (can be null, in which
     *            case the system default is looked up via the line.separator property)
     */
    public XmlPrettyPrinter(XmlFormatPreferences prefs, XmlFormatStyle style,
            String lineSeparator) {
        mPrefs = prefs;
        mStyle = style;
        if (lineSeparator == null) {
            lineSeparator = SdkUtils.getLineSeparator();
        }
        mLineSeparator = lineSeparator;
    }

    /**
     * Sets whether the document should end with a newline/ line separator
     *
     * @param endWithNewline if true, ensure that the document ends with a newline
     * @return this, for constructor chaining
     */
    public XmlPrettyPrinter setEndWithNewline(boolean endWithNewline) {
        mEndWithNewline = endWithNewline;
        return this;
    }

    /**
     * Sets the indentation levels to use (indentation string to use for each depth,
     * indexed by depth
     *
     * @param indentationLevels an array of strings to use for the various indentation
     *            levels
     */
    public void setIndentationLevels(String[] indentationLevels) {
        mIndentationLevels = indentationLevels;
    }

    @NonNull
    private String getLineSeparator() {
        return mLineSeparator;
    }

    /**
     * Pretty-prints the given XML document, which must be well-formed. If it is not,
     * the original unformatted XML document is returned
     *
     * @param xml the XML content to format
     * @param prefs the preferences to format with
     * @param style the style to format with
     * @param lineSeparator the line separator to use, such as "\n" (can be null, in which
     *     case the system default is looked up via the line.separator property)
     * @return the formatted document (or if a parsing error occurred, returns the
     *     unformatted document)
     */
    @NonNull
    public static String prettyPrint(
            @NonNull String xml,
            @NonNull XmlFormatPreferences prefs,
            @NonNull XmlFormatStyle style,
            @Nullable String lineSeparator) {
        Document document = XmlUtils.parseDocumentSilently(xml, true);
        if (document != null) {
            XmlPrettyPrinter printer = new XmlPrettyPrinter(prefs, style, lineSeparator);
            printer.setEndWithNewline(xml.endsWith(printer.getLineSeparator()));
            StringBuilder sb = new StringBuilder(3 * xml.length() / 2);
            printer.prettyPrint(-1, document, null, null, sb, false /*openTagOnly*/);
            return sb.toString();
        } else {
            // Parser error: just return the unformatted content
            return xml;
        }
    }

    /**
     * Pretty prints the given node
     *
     * @param node the node, usually a document, to be printed
     * @param prefs the formatting preferences
     * @param style the formatting style to use
     * @param lineSeparator the line separator to use, or null to use the
     *            default
     * @return a formatted string
     * @deprecated Use {@link #prettyPrint(Node, XmlFormatPreferences,
     *      XmlFormatStyle, String, boolean)} instead
     */
    @NonNull
    @Deprecated
    public static String prettyPrint(
            @NonNull Node node,
            @NonNull XmlFormatPreferences prefs,
            @NonNull XmlFormatStyle style,
            @Nullable String lineSeparator) {
        return prettyPrint(node, prefs, style, lineSeparator, false);
    }

    /**
     * Pretty prints the given node
     *
     * @param node the node, usually a document, to be printed
     * @param prefs the formatting preferences
     * @param style the formatting style to use
     * @param lineSeparator the line separator to use, or null to use the
     *            default
     * @param endWithNewline if true, ensure that the printed output ends with a newline
     * @return a formatted string
     */
    @NonNull
    public static String prettyPrint(
            @NonNull Node node,
            @NonNull XmlFormatPreferences prefs,
            @NonNull XmlFormatStyle style,
            @Nullable String lineSeparator,
            boolean endWithNewline) {
        XmlPrettyPrinter printer = new XmlPrettyPrinter(prefs, style, lineSeparator);
        printer.setEndWithNewline(endWithNewline);
        StringBuilder sb = new StringBuilder(1000);
        printer.prettyPrint(-1, node, null, null, sb, false /*openTagOnly*/);
        String xml = sb.toString();
        if (node.getNodeType() == Node.DOCUMENT_NODE && !xml.startsWith("<?")) { //$NON-NLS-1$
            xml = XML_PROLOG + xml;
        }
        return xml;
    }

    /**
     * Pretty prints the given node using default styles
     *
     * @param node the node, usually a document, to be printed
     * @return the resulting formatted string
     * @deprecated Use {@link #prettyPrint(Node, boolean)} instead
     */
    @NonNull
    @Deprecated
    public static String prettyPrint(@NonNull Node node) {
        return prettyPrint(node, false);
    }

    /**
     * Pretty prints the given node using default styles
     *
     * @param node the node, usually a document, to be printed
     * @param endWithNewline if true, ensure that the printed output ends with a newline
     * @return the resulting formatted string
     */
    @NonNull
    public static String prettyPrint(@NonNull Node node, boolean endWithNewline) {
        return prettyPrint(node, XmlFormatPreferences.defaults(), XmlFormatStyle.get(node),
                SdkUtils.getLineSeparator(), endWithNewline);
    }

    /**
     * Start pretty-printing at the given node, which must either be the
     * startNode or contain it as a descendant.
     *
     * @param rootDepth the depth of the given node, used to determine indentation
     * @param root the node to start pretty printing from (which may not itself be
     *            included in the start to end node range but should contain it)
     * @param startNode the node to start formatting at
     * @param endNode the node to end formatting at
     * @param out the {@link StringBuilder} to pretty print into
     * @param openTagOnly if true, only format the open tag of the startNode (and nothing
     *     else)
     */
    public void prettyPrint(int rootDepth, Node root, Node startNode, Node endNode,
            StringBuilder out, boolean openTagOnly) {
        if (startNode == null) {
            startNode = root;
        }
        if (endNode == null) {
            endNode = root;
        }
        assert !openTagOnly || startNode == endNode;

        mStartNode = startNode;
        mOpenTagOnly = openTagOnly;
        mEndNode = endNode;
        mOut = out;
        mInRange = false;
        mIndentString = mPrefs.getOneIndentUnit();

        visitNode(rootDepth, root);

        if (mEndWithNewline && !endsWithLineSeparator()) {
            mOut.append(mLineSeparator);
        }
    }

    /** Visit the given node at the given depth */
    private void visitNode(int depth, Node node) {
        if (node == mStartNode) {
            mInRange = true;
        }

        if (mInRange) {
            visitBeforeChildren(depth, node);
            if (mOpenTagOnly && mStartNode == node) {
                mInRange = false;
                return;
            }
        }

        NodeList children = node.getChildNodes();
        for (int i = 0, n = children.getLength(); i < n; i++) {
            Node child = children.item(i);
            visitNode(depth + 1, child);
        }

        if (mInRange) {
            visitAfterChildren(depth, node);
        }

        if (node == mEndNode) {
            mInRange = false;
        }
    }

    private void visitBeforeChildren(int depth, Node node) {
        short type = node.getNodeType();
        switch (type) {
            case Node.DOCUMENT_NODE:
            case Node.DOCUMENT_FRAGMENT_NODE:
                // Nothing to do
                break;

            case Node.ATTRIBUTE_NODE:
                // Handled as part of processing elements
                break;

            case Node.ELEMENT_NODE: {
                printOpenElementTag(depth, node);
                break;
            }

            case Node.TEXT_NODE: {
                printText(node);
                break;
            }

            case Node.CDATA_SECTION_NODE:
                printCharacterData(node);
                break;

            case Node.PROCESSING_INSTRUCTION_NODE:
                printProcessingInstruction(node);
                break;

            case Node.COMMENT_NODE: {
                printComment(depth, node);
                break;
            }

            case Node.DOCUMENT_TYPE_NODE:
                printDocType(node);
                break;

            case Node.ENTITY_REFERENCE_NODE:
            case Node.ENTITY_NODE:
            case Node.NOTATION_NODE:
                break;
            default:
                assert false : type;
        }
    }

    private void visitAfterChildren(int depth, Node node) {
        short type = node.getNodeType();
        switch (type) {
            case Node.ATTRIBUTE_NODE:
                // Handled as part of processing elements
                break;
            case Node.ELEMENT_NODE: {
                printCloseElementTag(depth, node);
                break;
            }
        }
    }

    private void printProcessingInstruction(Node node) {
        mOut.append("<?xml "); //$NON-NLS-1$
        mOut.append(node.getNodeValue().trim());
        mOut.append('?').append('>').append(mLineSeparator);
    }

    @Nullable
    @SuppressWarnings("MethodMayBeStatic") // Intentionally instance method so it can be overridden
    protected String getSource(@NonNull Node node) {
        return null;
    }

    private void printDocType(Node node) {
        String content = getSource(node);
        if (content != null) {
            mOut.append(content);
            mOut.append(mLineSeparator);
        }
    }

    private void printCharacterData(Node node) {
        String nodeValue = node.getNodeValue();
        boolean separateLine = nodeValue.indexOf('\n') != -1;
        if (separateLine && !endsWithLineSeparator()) {
            mOut.append(mLineSeparator);
        }
        mOut.append("<![CDATA["); //$NON-NLS-1$
        mOut.append(nodeValue);
        mOut.append("]]>");       //$NON-NLS-1$
        if (separateLine) {
            mOut.append(mLineSeparator);
        }
    }

    private void printText(Node node) {
        boolean escape = true;
        String text = node.getNodeValue();

        String source = getSource(node);
        if (source != null) {
            // Get the original source string. This will contain the actual entities
            // such as "&gt;" instead of ">" which it gets turned into for the DOM nodes.
            // By operating on source we can preserve the user's entities rather than
            // having &gt; for example always turned into >.
            text = source;
            escape = false;
        }

        // Most text nodes are just whitespace for formatting (which we're replacing)
        // so look for actual text content and extract that part out
        String trimmed = text.trim();
        if (!trimmed.isEmpty()) {
            // TODO: Reformat the contents if it is too wide?

            // Note that we append the actual text content, NOT the trimmed content,
            // since the whitespace may be significant, e.g.
            // <string name="toast_sync_error">Sync error: <xliff:g id="error">%1$s</xliff:g>...

            // However, we should remove all blank lines in the prefix and suffix of the
            // text node, or we will end up inserting additional blank lines each time you're
            // formatting a text node within an outer element (which also adds spacing lines)
            int lastPrefixNewline = -1;
            for (int i = 0, n = text.length(); i < n; i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    lastPrefixNewline = i;
                } else if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            int firstSuffixNewline = -1;
            for (int i = text.length() - 1; i >= 0; i--) {
                char c = text.charAt(i);
                if (c == '\n') {
                    firstSuffixNewline = i;
                } else if (!Character.isWhitespace(c)) {
                    break;
                }
            }
            if (lastPrefixNewline != -1 || firstSuffixNewline != -1) {
                boolean stripSuffix;
                if (firstSuffixNewline == -1) {
                    firstSuffixNewline = text.length();
                    stripSuffix = false;
                } else {
                    stripSuffix = true;
                }

                int stripFrom = lastPrefixNewline + 1;
                if (firstSuffixNewline >= stripFrom) {
                    text = text.substring(stripFrom, firstSuffixNewline);

                    // In markup strings we may need to preserve spacing on the left and/or
                    // right if we're next to a markup string on the given side
                    if (lastPrefixNewline != -1) {
                        Node left = node.getPreviousSibling();
                        if (left != null && left.getNodeType() == Node.ELEMENT_NODE
                                && isMarkupElement((Element) left)) {
                            text = ' ' + text;
                        }
                    }
                    if (stripSuffix) {
                        Node right = node.getNextSibling();
                        if (right != null && right.getNodeType() == Node.ELEMENT_NODE
                                && isMarkupElement((Element) right)) {
                            text += ' ';
                        }
                    }
                }
            }

            if (escape) {
                XmlUtils.appendXmlTextValue(mOut, text);
            } else {
                // Text is already escaped
                mOut.append(text);
            }

            if (mStyle != XmlFormatStyle.RESOURCE) {
                mOut.append(mLineSeparator);
            }
        } else {
            // Ensure that if we're in the middle of a markup string, we preserve spacing.
            // In other words, "<b>first</b> <b>second</b>" - we don't want that middle
            // space to disappear, but we do want repeated spaces to collapse into one.
            Node left = node.getPreviousSibling();
            Node right = node.getNextSibling();
            if (left != null && right != null
                    && left.getNodeType() == Node.ELEMENT_NODE
                    && right.getNodeType() == Node.ELEMENT_NODE
                    && isMarkupElement((Element)left)) {
                mOut.append(' ');
            }
        }
    }

    private void printComment(int depth, Node node) {
        String comment = node.getNodeValue();
        boolean multiLine = comment.indexOf('\n') != -1;
        String trimmed = comment.trim();

        // See if this is an "end-of-the-line" comment, e.g. it is not a multi-line
        // comment and it appears on the same line as an opening or closing element tag;
        // if so, continue to place it as a suffix comment
        boolean isSuffixComment = false;
        if (!multiLine) {
            Node previous = node.getPreviousSibling();
            isSuffixComment = true;
            if (previous == null && node.getParentNode().getNodeType() == Node.DOCUMENT_NODE) {
                isSuffixComment = false;
            }
            while (previous != null) {
                short type = previous.getNodeType();
                if (type == Node.COMMENT_NODE) {
                    isSuffixComment = false;
                    break;
                } else if (type == Node.TEXT_NODE) {
                    if (previous.getNodeValue().indexOf('\n') != -1) {
                        isSuffixComment = false;
                        break;
                    }
                } else {
                    break;
                }
                previous = previous.getPreviousSibling();
            }
            if (isSuffixComment) {
                // Remove newline added by element open tag or element close tag
                if (endsWithLineSeparator()) {
                    removeLastLineSeparator();
                }
                mOut.append(' ');
            }
        }

        // Put the comment on a line on its own? Only if it was separated by a blank line
        // in the previous version of the document. In other words, if the document
        // adds blank lines between comments this formatter will preserve that fact, and vice
        // versa for a tightly formatted document it will preserve that convention as well.
        if (!mPrefs.removeEmptyLines && !isSuffixComment) {
            Node curr = node.getPreviousSibling();
            if (curr == null) {
                if (mOut.length() > 0 && !endsWithLineSeparator()) {
                    mOut.append(mLineSeparator);
                }
            } else if (curr.getNodeType() == Node.TEXT_NODE) {
                String text = curr.getNodeValue();
                // Count how many newlines we find in the trailing whitespace of the
                // text node
                int newLines = 0;
                for (int i = text.length() - 1; i >= 0; i--) {
                    char c = text.charAt(i);
                    if (Character.isWhitespace(c)) {
                        if (c == '\n') {
                            newLines++;
                            if (newLines == 2) {
                                break;
                            }
                        }
                    } else {
                        break;
                    }
                }
                if (newLines >= 2) {
                    mOut.append(mLineSeparator);
                } else if (text.trim().isEmpty() && curr.getPreviousSibling() == null) {
                    // Comment before first child in node
                    mOut.append(mLineSeparator);
                }
            }
        }


        // TODO: Reformat the comment text?
        if (!multiLine) {
            if (!isSuffixComment) {
                indent(depth);
            }
            mOut.append(XML_COMMENT_BEGIN).append(' ');
            mOut.append(trimmed);
            mOut.append(' ').append(XML_COMMENT_END);
            mOut.append(mLineSeparator);
        } else {
            // Strip off blank lines at the beginning and end of the comment text.
            // Find last newline at the beginning of the text:
            int index = 0;
            int end = comment.length();
            int recentNewline = -1;
            while (index < end) {
                char c = comment.charAt(index);
                if (c == '\n') {
                    recentNewline = index;
                }
                if (!Character.isWhitespace(c)) {
                    break;
                }
                index++;
            }

            int start = recentNewline + 1;

            // Find last newline at the end of the text
            index = end - 1;
            recentNewline = -1;
            while (index > start) {
                char c = comment.charAt(index);
                if (c == '\n') {
                    recentNewline = index;
                }
                if (!Character.isWhitespace(c)) {
                    break;
                }
                index--;
            }

            end = recentNewline == -1 ? index + 1 : recentNewline;
            if (start >= end) {
                // It's a blank comment like <!-- \n\n--> - just clean it up
                if (!isSuffixComment) {
                    indent(depth);
                }
                mOut.append(XML_COMMENT_BEGIN).append(' ').append(XML_COMMENT_END);
                mOut.append(mLineSeparator);
                return;
            }

            trimmed = comment.substring(start, end);

            // When stripping out prefix and suffix blank lines we might have ended up
            // with a single line comment again so check and format single line comments
            // without newlines inside the <!-- --> delimiters
            multiLine = trimmed.indexOf('\n') != -1;
            if (multiLine) {
                indent(depth);
                mOut.append(XML_COMMENT_BEGIN);
                mOut.append(mLineSeparator);

                // See if we need to add extra spacing to keep alignment. Consider a comment
                // like this:
                // <!-- Deprecated strings - Move the identifiers to this section,
                //      and remove the actual text. -->
                // This String will be
                // " Deprecated strings - Move the identifiers to this section,\n" +
                // "     and remove the actual text. -->"
                // where the left side column no longer lines up.
                // To fix this, we need to insert some extra whitespace into the first line
                // of the string; in particular, the exact number of characters that the
                // first line of the comment was indented with!

                // However, if the comment started like this:
                // <!--
                // /** Copyright
                // -->
                // then obviously the align-indent is 0, so we only want to compute an
                // align indent when we don't find a newline before the content
                boolean startsWithNewline = false;
                for (int i = 0; i < start; i++) {
                    if (comment.charAt(i) == '\n') {
                        startsWithNewline = true;
                        break;
                    }
                }
                if (!startsWithNewline) {
                    Node previous = node.getPreviousSibling();
                    if (previous != null && previous.getNodeType() == Node.TEXT_NODE) {
                        String prevText = previous.getNodeValue();
                        int indentation = XML_COMMENT_BEGIN.length();
                        for (int i = prevText.length() - 1; i >= 0; i--) {
                            char c = prevText.charAt(i);
                            if (c == '\n') {
                                break;
                            } else {
                                indentation += (c == '\t') ? mPrefs.getTabWidth() : 1;
                            }
                        }

                        // See if the next line after the newline has indentation; if it doesn't,
                        // leave things alone. This fixes a case like this:
                        //     <!-- This is the
                        //     comment block -->
                        // such that it doesn't turn it into
                        //     <!--
                        //          This is the
                        //     comment block
                        //     -->
                        // In this case we instead want
                        //     <!--
                        //     This is the
                        //     comment block
                        //     -->
                        int minIndent = Integer.MAX_VALUE;
                        String[] lines = trimmed.split("\n"); //$NON-NLS-1$
                        // Skip line 0 since we know that it doesn't start with a newline
                        for (int i = 1; i < lines.length; i++) {
                            int indent = 0;
                            String line = lines[i];
                            for (int j = 0; j < line.length(); j++) {
                                char c = line.charAt(j);
                                if (!Character.isWhitespace(c)) {
                                    // Only set minIndent if there's text content on the line;
                                    // blank lines can exist in the comment without affecting
                                    // the overall minimum indentation boundary.
                                    if (indent < minIndent) {
                                        minIndent = indent;
                                    }
                                    break;
                                } else {
                                    indent += (c == '\t') ? mPrefs.getTabWidth() : 1;
                                }
                            }
                        }

                        if (minIndent < indentation) {
                            indentation = minIndent;

                            // Subtract any indentation that is already present on the line
                            String line = lines[0];
                            for (int j = 0; j < line.length(); j++) {
                                char c = line.charAt(j);
                                if (!Character.isWhitespace(c)) {
                                    break;
                                } else {
                                    indentation -= (c == '\t') ? mPrefs.getTabWidth() : 1;
                                }
                            }
                        }

                        for (int i = 0; i < indentation; i++) {
                            mOut.append(' ');
                        }

                        if (indentation < 0) {
                            boolean prefixIsSpace = true;
                            for (int i = 0; i < -indentation && i < trimmed.length(); i++) {
                                if (!Character.isWhitespace(trimmed.charAt(i))) {
                                    prefixIsSpace = false;
                                    break;
                                }
                            }
                            if (prefixIsSpace) {
                                trimmed = trimmed.substring(-indentation);
                            }
                        }
                    }
                }

                mOut.append(trimmed);
                mOut.append(mLineSeparator);
                indent(depth);
                mOut.append(XML_COMMENT_END);
                mOut.append(mLineSeparator);
            } else {
                mOut.append(XML_COMMENT_BEGIN).append(' ');
                mOut.append(trimmed);
                mOut.append(' ').append(XML_COMMENT_END);
                mOut.append(mLineSeparator);
            }
        }

        // Preserve whitespace after comment: See if the original document had two or
        // more newlines after the comment, and if so have a blank line between this
        // comment and the next
        Node next = node.getNextSibling();
        if (!mPrefs.removeEmptyLines && (next != null)
                && (next.getNodeType() == Node.TEXT_NODE)) {
            String text = next.getNodeValue();
            int newLinesBeforeText = 0;
            for (int i = 0, n = text.length(); i < n; i++) {
                char c = text.charAt(i);
                if (c == '\n') {
                    newLinesBeforeText++;
                    if (newLinesBeforeText == 2) {
                        // Yes
                        mOut.append(mLineSeparator);
                        break;
                    }
                } else if (!Character.isWhitespace(c)) {
                    break;
                }
            }
        }
    }

    private boolean endsWithLineSeparator() {
        int separatorLength = mLineSeparator.length();
        if (mOut.length() >= separatorLength) {
            for (int i = 0, j = mOut.length() - separatorLength; i < separatorLength; i++, j++) {
                if (mOut.charAt(j) != mLineSeparator.charAt(i)) {
                    return false;
                }
            }
        }

        return true;
    }

    private void removeLastLineSeparator() {
        int newLength = mOut.length() - mLineSeparator.length();
        if (newLength >= 0) {
            mOut.setLength(newLength);
        }
    }

    private void printOpenElementTag(int depth, Node node) {
        Element element = (Element) node;
        if (newlineBeforeElementOpen(element, depth)) {
            mOut.append(mLineSeparator);
        }
        if (indentBeforeElementOpen(element, depth)) {
            indent(depth);
        }
        mOut.append('<').append(element.getTagName());

        NamedNodeMap attributes = element.getAttributes();
        int attributeCount = attributes.getLength();
        if (attributeCount > 0) {
            // Sort the attributes
            List<Attr> attributeList = new ArrayList<Attr>();
            for (int i = 0; i < attributeCount; i++) {
                attributeList.add((Attr) attributes.item(i));
            }
            Comparator<Attr> comparator = mPrefs.getAttributeComparator();
            if (comparator != null) {
                Collections.sort(attributeList, comparator);
            }

            // Put the single attribute on the same line as the element tag?
            boolean singleLine = mPrefs.oneAttributeOnFirstLine && attributeCount == 1
                    // In resource files we always put all the attributes (which is
                    // usually just zero, one or two) on the same line
                    || mStyle == XmlFormatStyle.RESOURCE;

            // We also place the namespace declaration on the same line as the root element,
            // but this doesn't also imply singleLine handling; subsequent attributes end up
            // on their own lines
            boolean indentNextAttribute;
            if (singleLine || (depth == 0 && XMLNS.equals(attributeList.get(0).getPrefix()))) {
                mOut.append(' ');
                indentNextAttribute = false;
            } else {
                mOut.append(mLineSeparator);
                indentNextAttribute = true;
            }

            Attr last = attributeList.get(attributeCount - 1);
            for (Attr attribute : attributeList) {
                if (indentNextAttribute) {
                    indent(depth + 1);
                }
                mOut.append(attribute.getName());
                mOut.append('=').append('"');
                XmlUtils.appendXmlAttributeValue(mOut, attribute.getValue());
                mOut.append('"');

                // Don't add a newline at the last attribute line; the > should
                // immediately follow the last attribute
                if (attribute != last) {
                    mOut.append(singleLine ? " " : mLineSeparator); //$NON-NLS-1$
                    indentNextAttribute = !singleLine;
                }
            }
        }

        boolean isClosed = isEmptyTag(element);

        // Add a space before the > or /> ? In resource files, only do this when closing the
        // element
        if (mPrefs.spaceBeforeClose && (mStyle != XmlFormatStyle.RESOURCE || isClosed)
                // in <selector> files etc still treat the <item> entries as in resource files
                && !TAG_ITEM.equals(element.getTagName())
                && (isClosed || element.getAttributes().getLength() > 0)) {
            mOut.append(' ');
        }

        if (isClosed) {
            mOut.append('/');
        }

        mOut.append('>');

        if (newlineAfterElementOpen(element, depth, isClosed)) {
            mOut.append(mLineSeparator);
        }
    }

    private void printCloseElementTag(int depth, Node node) {
        Element element = (Element) node;
        if (isEmptyTag(element)) {
            // Empty tag: Already handled as part of opening tag
            return;
        }

        // Put the closing declaration on its own line - unless it's a compact
        // resource file format
        // If the element had element children, separate the end tag from them
        if (newlineBeforeElementClose(element, depth)) {
            mOut.append(mLineSeparator);
        }
        if (indentBeforeElementClose(element, depth)) {
            indent(depth);
        }
        mOut.append('<').append('/');
        mOut.append(node.getNodeName());
        mOut.append('>');

        if (newlineAfterElementClose(element, depth)) {
            mOut.append(mLineSeparator);
        }
    }

    private boolean newlineBeforeElementOpen(Element element, int depth) {
        if (hasBlankLineAbove()) {
            return false;
        }

        if (mPrefs.removeEmptyLines || depth <= 0) {
            return false;
        }

        if (isMarkupElement(element)) {
            return false;
        }

        // See if this element should be separated from the previous element.
        // This is the case if we are not compressing whitespace (checked above),
        // or if we are not immediately following a comment (in which case the
        // newline would have been added above it), or if we are not in a formatting
        // style where
        if (mStyle == XmlFormatStyle.LAYOUT) {
            // In layouts we always separate elements
            return true;
        }

        if (mStyle == XmlFormatStyle.MANIFEST || mStyle == XmlFormatStyle.RESOURCE
                || mStyle == XmlFormatStyle.FILE) {
            Node curr = element.getPreviousSibling();

            // <style> elements are traditionally separated unless it follows a comment
            if (TAG_STYLE.equals(element.getTagName())) {
                if (curr == null
                        || curr.getNodeType() == Node.ELEMENT_NODE
                        || (curr.getNodeType() == Node.TEXT_NODE
                        && curr.getNodeValue().trim().isEmpty()
                        && (curr.getPreviousSibling() == null
                        || curr.getPreviousSibling().getNodeType()
                        == Node.ELEMENT_NODE))) {
                    return true;
                }
            }

            // In all other styles, we separate elements if they have a different tag than
            // the previous one (but we don't insert a newline inside tags)
            while (curr != null) {
                short nodeType = curr.getNodeType();
                if (nodeType == Node.ELEMENT_NODE) {
                    Element sibling = (Element) curr;
                    if (!element.getTagName().equals(sibling.getTagName())) {
                        return true;
                    }
                    break;
                } else if (nodeType == Node.TEXT_NODE) {
                    String text = curr.getNodeValue();
                    if (!text.trim().isEmpty()) {
                        break;
                    }
                    // If there is just whitespace, continue looking for a previous sibling
                } else {
                    // Any other previous node type, such as a comment, means we don't
                    // continue looking: this element should not be separated
                    break;
                }
                curr = curr.getPreviousSibling();
            }
            if (curr == null && depth <= 1) {
                // Insert new line inside tag if it's the first element inside the root tag
                return true;
            }

            return false;
        }

        return false;
    }

    private boolean indentBeforeElementOpen(Element element, int depth) {
        if (isMarkupElement(element)) {
            return false;
        }

        if (element.getParentNode().getNodeType() == Node.ELEMENT_NODE
                && keepElementAsSingleLine(depth - 1, (Element) element.getParentNode())) {
            return false;
        }

        return true;
    }

    private boolean indentBeforeElementClose(Element element, int depth) {
        if (isMarkupElement(element)) {
            return false;
        }

        char lastOutChar = mOut.charAt(mOut.length() - 1);
        char lastDelimiterChar = mLineSeparator.charAt(mLineSeparator.length() - 1);
        return lastOutChar == lastDelimiterChar;
    }

    private boolean newlineAfterElementOpen(Element element, int depth, boolean isClosed) {
        if (hasBlankLineAbove()) {
            return false;
        }

        if (isMarkupElement(element)) {
            return false;
        }

        // In resource files we keep the child content directly on the same
        // line as the element (unless it has children). in other files, separate them
        return isClosed || !keepElementAsSingleLine(depth, element);
    }

    private boolean newlineBeforeElementClose(Element element, int depth) {
        if (hasBlankLineAbove()) {
            return false;
        }

        if (isMarkupElement(element)) {
            return false;
        }

        return depth == 0 && !mPrefs.removeEmptyLines;
    }

    private boolean hasBlankLineAbove() {
        if (mOut.length() < 2 * mLineSeparator.length()) {
            return false;
        }

        return SdkUtils.endsWith(mOut, mLineSeparator) &&
                SdkUtils.endsWith(mOut, mOut.length() - mLineSeparator.length(), mLineSeparator);
    }

    private boolean newlineAfterElementClose(Element element, int depth) {
        if (hasBlankLineAbove()) {
            return false;
        }

        if (isMarkupElement(element)) {
            return false;
        }

        return element.getParentNode().getNodeType() == Node.ELEMENT_NODE
                && !keepElementAsSingleLine(depth - 1, (Element) element.getParentNode());
    }

    private boolean isMarkupElement(Element element) {
        // The documentation suggests that the allowed tags are <u>, <b> and <i>:
        //   developer.android.com/guide/topics/resources/string-resource.html#FormattingAndStyling
        // However, the full set of tags accepted by Html.fromHtml is much larger. Therefore,
        // instead consider *any* element nested inside a <string> definition to be a markup
        // element. See frameworks/base/core/java/android/text/Html.java and look for
        // HtmlToSpannedConverter#handleStartTag.

        if (mStyle != XmlFormatStyle.RESOURCE) {
            return false;
        }

        Node curr = element.getParentNode();
        while (curr != null) {
            if (TAG_STRING.equals(curr.getNodeName())) {
                return true;
            }

            curr = curr.getParentNode();
        }

        return false;
    }

    /**
     * TODO: Explain why we need to do per-tag decisions on whether to keep them on the
     * same line or not. Show that we can't just do it by depth, or by file type.
     * (style versus plurals example)
     * @param element the element whose tag we want to check
     * @return true if the element is a single line tag
     */
    private boolean isSingleLineTag(Element element) {
        String tag = element.getTagName();

        return (tag.equals(TAG_ITEM) && mStyle == XmlFormatStyle.RESOURCE)
                || tag.equals(TAG_STRING)
                || tag.equals(TAG_DIMEN)
                || tag.equals(TAG_COLOR);
    }

    private boolean keepElementAsSingleLine(int depth, Element element) {
        if (depth == 0) {
            return false;
        }

        return isSingleLineTag(element)
                || (mStyle == XmlFormatStyle.RESOURCE
                && !XmlUtils.hasElementChildren(element));
    }

    private void indent(int depth) {
        int i = 0;

        if (mIndentationLevels != null) {
            for (int j = Math.min(depth, mIndentationLevels.length - 1); j >= 0; j--) {
                String indent = mIndentationLevels[j];
                if (indent != null) {
                    mOut.append(indent);
                    i = j;
                    break;
                }
            }
        }

        for (; i < depth; i++) {
            mOut.append(mIndentString);
        }
    }

    /**
     * Returns true if the given element should be an empty tag
     *
     * @param element the element to test
     * @return true if this element should be an empty tag
     */
    @SuppressWarnings("MethodMayBeStatic") // Intentionally instance method so it can be overridden
    protected boolean isEmptyTag(Element element) {
        if (element.getFirstChild() != null) {
            return false;
        }

        String tag = element.getTagName();
        if (TAG_STRING.equals(tag)) {
            return false;
        }

        return true;
    }

    private static void printUsage() {
        System.out.println("Usage: " + XmlPrettyPrinter.class.getSimpleName() +
            " <options>... <files or directories...>");
        System.out.println("OPTIONS:");
        System.out.println("--stdout");
        System.out.println("--removeEmptyLines");
        System.out.println("--noAttributeOnFirstLine");
        System.out.println("--noSpaceBeforeClose");
        System.exit(1);
    }

    /** Command line driver */
    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage();
        }

        List<File> files = Lists.newArrayList();

        XmlFormatPreferences prefs = XmlFormatPreferences.defaults();
        boolean stdout = false;

        for (String arg : args) {
            if (arg.startsWith("--")) {
                if ("--stdout".equals(arg)) {
                    stdout = true;
                } else if ("--removeEmptyLines".equals(arg)) {
                    prefs.removeEmptyLines = true;
                } else if ("--noAttributeOnFirstLine".equals(arg)) {
                    prefs.oneAttributeOnFirstLine = false;
                } else if ("--noSpaceBeforeClose".equals(arg)) {
                    prefs.spaceBeforeClose = false;
                } else {
                    System.err.println("Unknown flag " + arg);
                    printUsage();
                }
            } else {
                File file = new File(arg).getAbsoluteFile();
                if (!file.exists()) {
                    System.err.println("Can't find file " + file);
                    System.exit(1);
                } else {
                    files.add(file);
                }
            }
        }

        for (File file : files) {
            formatFile(prefs, file, stdout);
        }

        System.exit(0);
    }

    private static void formatFile(@NonNull XmlFormatPreferences prefs, File file,
            boolean stdout) {
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) {
                    formatFile(prefs, child, stdout);
                }
            }
        } else if (file.isFile() && SdkUtils.endsWithIgnoreCase(file.getName(), DOT_XML)) {
            XmlFormatStyle style = null;
            if (file.getName().equals(SdkConstants.ANDROID_MANIFEST_XML)) {
                style = XmlFormatStyle.MANIFEST;
            } else {
                File parent = file.getParentFile();
                if (parent != null) {
                    String parentName = parent.getName();
                    ResourceFolderType folderType = ResourceFolderType.getFolderType(parentName);
                    if (folderType == ResourceFolderType.LAYOUT) {
                        style = XmlFormatStyle.LAYOUT;
                    } else if (folderType == ResourceFolderType.VALUES) {
                        style = XmlFormatStyle.RESOURCE;
                    }
                }
            }

            try {
                String xml = Files.toString(file, Charsets.UTF_8);
                Document document = XmlUtils.parseDocumentSilently(xml, true);
                if (document == null) {
                    System.err.println("Could not parse " + file);
                    System.exit(1);
                    return;
                }

                if (style == null) {
                    style = XmlFormatStyle.get(document);
                }
                boolean endWithNewline = xml.endsWith("\n");
                int firstNewLine = xml.indexOf('\n');
                String lineSeparator = firstNewLine > 0 && xml.charAt(firstNewLine - 1) == '\r' ?
                        "\r\n" : "\n";
                String formatted = XmlPrettyPrinter.prettyPrint(document, prefs, style,
                        lineSeparator, endWithNewline);
                if (stdout) {
                    System.out.println(formatted);
                } else {
                    Files.write(formatted, file, Charsets.UTF_8);
                }
            } catch (IOException e) {
                System.err.println("Could not read " + file);
                System.exit(1);
            }
        }
    }
}
