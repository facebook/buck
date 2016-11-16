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
import com.android.common.xml.AndroidManifest;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Style to use when printing the XML. Different types of Android XML files use slightly
 * different preferred formats. For example, in layout files there is typically always a
 * newline between successive elements, whereas in a manifest file there is typically only
 * newlines between different types of elements. As another example, in resource files,
 * the format is typically much more compact: the text content of {@code <item>} tags is
 * included on the same line whereas for other layout styles the children are typically
 * placed on a line of their own.
 */
public enum XmlFormatStyle {
    /** Layout formatting style: blank lines between elements, attributes on separate lines */
    LAYOUT,

    /** Similar to layout formatting style, but no blank lines inside opening elements */
    FILE,

    /** Resource style: one line per complete element including text child content */
    RESOURCE,

    /**
     * Similar to layout style, but no newlines between related elements such as
     * successive {@code <uses-permission>} declarations, and no newlines inside
     * the second level elements (so an {@code <activity>} declaration appears as a
     * single block with no whitespace within it)
     */
    MANIFEST;

    @NonNull
    public static XmlFormatStyle get(@Nullable Node node) {
        if (node != null) {
            Document doc = (node.getNodeType() == Node.DOCUMENT_NODE)
                    ? (Document) node : node.getOwnerDocument();
            if (doc != null) {
                Element root = doc.getDocumentElement();
                if (root != null) {
                    String tag = root.getTagName();
                    if (tag.equals(SdkConstants.TAG_RESOURCES)) {
                        return RESOURCE;
                    } else if (tag.equals(AndroidManifest.NODE_MANIFEST)) {
                        return MANIFEST;
                    }

                    // How do we detect a layout vs other files such as drawables??
                    // For now, assume that capitalized tags are view names, or names
                    // with package components are custom views
                    if (Character.isUpperCase(tag.charAt(0))
                            || SdkConstants.VIEW_TAG.equals(tag)
                            || SdkConstants.VIEW_INCLUDE.equals(tag)
                            || SdkConstants.VIEW_MERGE.equals(tag)
                            || tag.indexOf('.') != -1) {
                        return LAYOUT;
                    }
                }
            }
        }

        return FILE;
    }
}