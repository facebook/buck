/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.common.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.annotations.concurrency.Immutable;
import com.android.common.ide.common.blame.SourceFile;
import com.android.common.ide.common.blame.SourceFilePosition;
import com.android.common.ide.common.blame.SourcePosition;
import com.google.common.base.Function;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Common behavior of any xml declaration.
 */
public abstract class XmlNode {

    protected static final Function<Node, String> NODE_TO_NAME =
            new Function<Node, String>() {
                @Override
                public String apply(Node input) {
                    return input.getNodeName();
                }
            };

    @Nullable
    private NodeKey mOriginalId = null;

    /**
     * Returns a constant Nodekey that can be used throughout the lifecycle of the xml element.
     * The {@link #getId} can return different values over time as the key of the element can be
     * for instance, changed through placeholder replacement.
     */
    @NonNull
    public synchronized NodeKey getOriginalId() {
        if (mOriginalId == null) {
            mOriginalId = getId();
        }
        return mOriginalId;
    }

    /**
     * Returns an unique id within the manifest file for the element.
     */
    @NonNull
    public abstract NodeKey getId();

    /**
     * Returns the element's position
     */
    @NonNull
    public abstract SourcePosition getPosition();

    /**
     * Returns the element's document xml source file location.
     */
    @NonNull
    public abstract SourceFile getSourceFile();

    /**
     * Returns the element's document xml source file location.
     */
    @NonNull
    public SourceFilePosition getSourceFilePosition() {
        return new SourceFilePosition(getSourceFile(), getPosition());
    }

    /**
     * Returns the element's xml
     */
    @NonNull
    public abstract Node getXml();

    /**
     * Returns the name of this xml element or attribute.
     */
    @NonNull
    public abstract NodeName getName();

    /**
     * Abstraction to an xml name to isolate whether the name has a namespace or not.
     */
    public interface NodeName {

        /**
         * Returns true if this attribute name has a namespace declaration and that namespapce is
         * the same as provided, false otherwise.
         */
        boolean isInNamespace(@NonNull String namespaceURI);

        /**
         * Adds a new attribute of this name to a xml element with a value.
         * @param to the xml element to add the attribute to.
         * @param withValue the new attribute's value.
         */
        void addToNode(@NonNull Element to, String withValue);

        /**
         * The local name.
         */
        String getLocalName();
    }

    /**
     * Factory method to create an instance of {@link com.android.manifmerger.XmlNode.NodeName}
     * for an existing xml node.
     * @param node the xml definition.
     * @return an instance of {@link com.android.manifmerger.XmlNode.NodeName} providing
     * namespace handling.
     */
    @NonNull
    public static NodeName unwrapName(@NonNull Node node) {
        return node.getNamespaceURI() == null
                ? new Name(node.getNodeName())
                : new NamespaceAwareName(node);
    }

    @NonNull
    public static NodeName fromXmlName(@NonNull String name) {
        if (name.contains(":")) {
            String prefix = name.substring(0, name.indexOf(':'));
            return new NamespaceAwareName(
              SdkConstants.XMLNS.equals(prefix) ? SdkConstants.XMLNS_URI : SdkConstants.ANDROID_URI,
              prefix, name.substring(name.indexOf(':') + 1));
        }
        return new Name(name);
    }

    @NonNull
    public static NodeName fromNSName(
            @NonNull String namespaceUri, @NonNull String prefix, @NonNull String localName) {
        return new NamespaceAwareName(namespaceUri, prefix, localName);
    }

    /**
     * Returns the position of this attribute in the original xml file. This may return an invalid
     * location as this xml fragment does not exist in any xml file but is the temporary result
     * of the merging process.
     * @return a human readable position.
     */
    @NonNull
    public String printPosition() {
        return getSourceFilePosition().print(true /*shortFormat*/);
    }

    /**
     * Implementation of {@link com.android.manifmerger.XmlNode.NodeName} for an
     * node's declaration not using a namespace.
     */
    public static final class Name implements NodeName {
        private final String mName;

        private Name(@NonNull String name) {
            this.mName = Preconditions.checkNotNull(name);
        }

        @Override
        public boolean isInNamespace(@NonNull String namespaceURI) {
            return false;
        }

        @Override
        public void addToNode(@NonNull Element to, String withValue) {
            to.setAttribute(mName, withValue);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return (o != null && o instanceof Name && ((Name) o).mName.equals(this.mName));
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mName);
        }

        @Override
        public String toString() {
            return mName;
        }

        @Override
        public String getLocalName() {
            return mName;
        }
    }

    /**
     * Implementation of the {@link com.android.manifmerger.XmlNode.NodeName} for a namespace aware attribute.
     */
    public static final class NamespaceAwareName implements NodeName {

        @NonNull
        private final String mNamespaceURI;

        // ignore for comparison and hashcoding since different documents can use different
        // prefixes for the same namespace URI.
        @NonNull
        private final String mPrefix;
        @NonNull
        private final String mLocalName;

        private NamespaceAwareName(@NonNull Node node) {
            this.mNamespaceURI = Preconditions.checkNotNull(node.getNamespaceURI());
            this.mPrefix = Preconditions.checkNotNull(node.getPrefix());
            this.mLocalName = Preconditions.checkNotNull(node.getLocalName());
        }

        private NamespaceAwareName(@NonNull String namespaceURI,
                @NonNull String prefix,
                @NonNull String localName) {
            mNamespaceURI = Preconditions.checkNotNull(namespaceURI);
            mPrefix = Preconditions.checkNotNull(prefix);
            mLocalName = Preconditions.checkNotNull(localName);
        }

        @Override
        public boolean isInNamespace(@NonNull String namespaceURI) {
            return mNamespaceURI.equals(namespaceURI);
        }

        @Override
        public void addToNode(@NonNull Element to, String withValue) {
            // TODO: consider standardizing everything on "android:"
            to.setAttributeNS(mNamespaceURI, mPrefix + ":" + mLocalName, withValue);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mNamespaceURI, mLocalName);
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return (o != null && o instanceof NamespaceAwareName
                    && ((NamespaceAwareName) o).mLocalName.equals(this.mLocalName)
                    && ((NamespaceAwareName) o).mNamespaceURI.equals(this.mNamespaceURI));
        }

        @NonNull
        @Override
        public String toString() {
            return mPrefix + ":" + mLocalName;
        }

        @NonNull
        @Override
        public String getLocalName() {
            return mLocalName;
        }
    }

    /**
     * A xml element or attribute key.
     */
    @Immutable
    public static class NodeKey {

        @NonNull
        private final String mKey;

        NodeKey(@NonNull String key) {
            mKey = key;
        }

        public static NodeKey fromXml(@NonNull Element element) {
            return new OrphanXmlElement(element).getId();
        }

        @NonNull
        @Override
        public String toString() {
            return mKey;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            return (o != null && o instanceof NodeKey && ((NodeKey) o).mKey.equals(this.mKey));
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mKey);
        }
    }
}
