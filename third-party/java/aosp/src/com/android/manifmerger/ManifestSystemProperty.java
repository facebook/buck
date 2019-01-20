/*
 * Copyright (C) 2016 The Android Open Source Project
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
import com.android.common.ide.common.blame.SourceFilePosition;
import com.android.common.ide.common.blame.SourcePosition;
import com.android.common.utils.SdkUtils;
import com.android.common.utils.XmlUtils;

import org.w3c.dom.Attr;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * List of manifest files properties that can be directly overridden without using a
 * placeholder.
 */
public enum ManifestSystemProperty implements ManifestMerger2.AutoAddingProperty {

    /**
     * Allow setting the merged manifest file package name.
     */
    PACKAGE {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElement(this, actionRecorder, value, document.getRootNode());
        }
    },
    /**
     * @see <a href="http://developer.android.com/guide/topics/manifest/manifest-element.html#vcode">
     *     http://developer.android.com/guide/topics/manifest/manifest-element.html#vcode</a>
     */
    VERSION_CODE {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value, document.getRootNode());
        }
    },
    /**
     * @see <a href="http://developer.android.com/guide/topics/manifest/manifest-element.html#vname">
     *      http://developer.android.com/guide/topics/manifest/manifest-element.html#vname</a>
     */
    VERSION_NAME {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value, document.getRootNode());
        }
    },
    /**
     * @see <a href="http://developer.android.com/guide/topics/manifest/uses-sdk-element.html#min">
     *     http://developer.android.com/guide/topics/manifest/uses-sdk-element.html#min</a>
     */
    MIN_SDK_VERSION {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetUseSdk(actionRecorder, document));
        }
    },
    /**
     * @see <a href="http://developer.android.com/guide/topics/manifest/uses-sdk-element.html#target">
     *     http://developer.android.com/guide/topics/manifest/uses-sdk-element.html#target</a>
     */
    TARGET_SDK_VERSION {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetUseSdk(actionRecorder, document));
        }
    },
    /**
     * @see <a href="http://developer.android.com/guide/topics/manifest/uses-sdk-element.html#max">
     *     http://developer.android.com/guide/topics/manifest/uses-sdk-element.html#max</a>
     */
    MAX_SDK_VERSION {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetUseSdk(actionRecorder, document));
        }
    },
    /**
     * Name of the instrumentation runner.
     *
     * @see <a href="http://developer.android.com/guide/topics/manifest/instrumentation-element.html">
     *     http://developer.android.com/guide/topics/manifest/instrumentation-element.html</a>
     */
    NAME {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetInstrumentation(actionRecorder, document));
        }
    },
    /**
     * Target package for the instrumentation.
     *
     * @see <a href="http://developer.android.com/guide/topics/manifest/instrumentation-element.html">
     *     http://developer.android.com/guide/topics/manifest/instrumentation-element.html</a>
     */
    TARGET_PACKAGE {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetInstrumentation(actionRecorder, document));
        }
    },
    /**
     * Functional test attribute for the instrumentation.
     *
     * @see <a href="http://developer.android.com/guide/topics/manifest/instrumentation-element.html">
     *     http://developer.android.com/guide/topics/manifest/instrumentation-element.html</a>
     */
    FUNCTIONAL_TEST {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetInstrumentation(actionRecorder, document));
        }
    },
    /**
     * Handle profiling attribute for the instrumentation.
     *
     * @see <a href="http://developer.android.com/guide/topics/manifest/instrumentation-element.html">
     *     http://developer.android.com/guide/topics/manifest/instrumentation-element.html</a>
     */
    HANDLE_PROFILING {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetInstrumentation(actionRecorder, document));
        }
    },
    /**
     * Label attribute for the instrumentation.
     *
     * @see <a href="http://developer.android.com/guide/topics/manifest/instrumentation-element.html">
     *     http://developer.android.com/guide/topics/manifest/instrumentation-element.html</a>
     */
    LABEL {
        @Override
        public void addTo(@NonNull ActionRecorder actionRecorder,
                @NonNull XmlDocument document,
                @NonNull String value) {
            addToElementInAndroidNS(this, actionRecorder, value,
                    createOrGetInstrumentation(actionRecorder, document));
        }
    };

    public String toCamelCase() {
        return SdkUtils.constantNameToCamelCase(name());
    }

    // utility method to add an attribute which name is derived from the enum name().
    private static void addToElement(
            @NonNull ManifestSystemProperty manifestSystemProperty,
            @NonNull ActionRecorder actionRecorder,
            String value,
            @NonNull XmlElement to) {

        to.getXml().setAttribute(manifestSystemProperty.toCamelCase(), value);
        XmlAttribute xmlAttribute = new XmlAttribute(to,
                to.getXml().getAttributeNode(manifestSystemProperty.toCamelCase()), null);
        actionRecorder.recordAttributeAction(xmlAttribute, new Actions.AttributeRecord(
                Actions.ActionType.INJECTED,
                new SourceFilePosition(to.getSourceFile(), SourcePosition.UNKNOWN),
                xmlAttribute.getId(),
                null, /* reason */
                null /* attributeOperationType */));
    }

    // utility method to add an attribute in android namespace which local name is derived from
    // the enum name().
    private static void addToElementInAndroidNS(
            @NonNull ManifestSystemProperty manifestSystemProperty,
            @NonNull ActionRecorder actionRecorder,
            String value,
            @NonNull XmlElement to) {

        String toolsPrefix = getAndroidPrefix(to.getXml());
        to.getXml().setAttributeNS(SdkConstants.ANDROID_URI,
                toolsPrefix + XmlUtils.NS_SEPARATOR + manifestSystemProperty.toCamelCase(),
                value);
        Attr attr = to.getXml().getAttributeNodeNS(SdkConstants.ANDROID_URI,
                manifestSystemProperty.toCamelCase());

        XmlAttribute xmlAttribute = new XmlAttribute(to, attr, null);
        actionRecorder.recordAttributeAction(xmlAttribute,
                new Actions.AttributeRecord(
                        Actions.ActionType.INJECTED,
                        new SourceFilePosition(to.getSourceFile(), SourcePosition.UNKNOWN),
                        xmlAttribute.getId(),
                        null, /* reason */
                        null /* attributeOperationType */
                )
        );

    }

    // utility method to create or get an existing use-sdk xml element under manifest.
    // this could be made more generic by adding more metadata to the enum but since there is
    // only one case so far, keep it simple.
    @NonNull
    private static XmlElement createOrGetUseSdk(
            @NonNull ActionRecorder actionRecorder, @NonNull XmlDocument document) {
        return createOrGetElement(actionRecorder, document,
                ManifestModel.NodeTypes.USES_SDK, "use-sdk injection requested");
    }

    /** See above for details, similar like for uses-sdk tag*/
    @NonNull
    private static XmlElement createOrGetInstrumentation(
            @NonNull ActionRecorder actionRecorder, @NonNull XmlDocument document) {
        return createOrGetElement(actionRecorder, document,
                ManifestModel.NodeTypes.INSTRUMENTATION, "instrumentation injection requested");
    }

    @NonNull
    private static XmlElement createOrGetElement(
            @NonNull ActionRecorder actionRecorder, @NonNull XmlDocument document,
            @NonNull ManifestModel.NodeTypes nodeType, @NonNull String message) {

        Element manifest = document.getXml().getDocumentElement();
        NodeList nodes = manifest.getElementsByTagName(nodeType.toXmlName());
        if (nodes.getLength() == 0) {
            nodes = manifest.getElementsByTagNameNS(
                    SdkConstants.ANDROID_URI, nodeType.toXmlName());
        }
        if (nodes.getLength() == 0) {
            // create it first.
            Element node = manifest.getOwnerDocument().createElement(nodeType.toXmlName());
            manifest.appendChild(node);
            XmlElement xmlElement = new XmlElement(node, document);
            Actions.NodeRecord nodeRecord = new Actions.NodeRecord(
                    Actions.ActionType.INJECTED,
                    new SourceFilePosition(xmlElement.getSourceFile(), SourcePosition.UNKNOWN),
                    xmlElement.getId(),
                    message,
                    NodeOperationType.STRICT);
            actionRecorder.recordNodeAction(xmlElement, nodeRecord);
            return xmlElement;
        } else {
            return new XmlElement((Element) nodes.item(0), document);
        }
    }

    private static String getAndroidPrefix(@NonNull Element xml) {
        String toolsPrefix = XmlUtils.lookupNamespacePrefix(
                xml, SdkConstants.ANDROID_URI, SdkConstants.ANDROID_NS_NAME, false);
        if (!toolsPrefix.equals(SdkConstants.ANDROID_NS_NAME) && xml.getOwnerDocument()
                .getDocumentElement().getAttribute("xmlns:" + toolsPrefix) == null) {
            // this is weird, the document is using "android" prefix but it's not bound
            // to our namespace. Add the proper xmlns declaration.
            xml.setAttribute("xmlns:" + toolsPrefix, SdkConstants.ANDROID_URI);
        }
        return toolsPrefix;
    }

}