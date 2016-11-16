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

import static com.android.manifmerger.ManifestModel.NodeTypes.USES_PERMISSION;
import static com.android.manifmerger.ManifestModel.NodeTypes.USES_SDK;
import static com.android.manifmerger.PlaceholderHandler.KeyBasedValueResolver;

import com.android.common.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.common.ide.common.blame.SourceFile;
import com.android.common.ide.common.blame.SourceFilePosition;
import com.android.common.ide.common.blame.SourcePosition;
import com.android.common.ide.common.xml.XmlFormatPreferences;
import com.android.common.ide.common.xml.XmlFormatStyle;
import com.android.common.ide.common.xml.XmlPrettyPrinter;
import com.android.common.sdklib.SdkVersionInfo;
import com.android.common.utils.Pair;
import com.android.common.utils.PositionXmlParser;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a loaded xml document.
 *
 * Has pointers to the root {@link XmlElement} element and provides services to persist the document
 * to an external format. Also provides abilities to be merged with other
 * {@link com.android.manifmerger.XmlDocument} as well as access to the line numbers for all
 * document's xml elements and attributes.
 *
 */
public class XmlDocument {

    private static final String DEFAULT_SDK_VERSION = "1";

    /**
     * The document type.
     */
    enum Type {
        /**
         * A manifest overlay as found in the build types and variants.
         */
        OVERLAY,
        /**
         * The main android manifest file.
         */
        MAIN,
        /**
         * A library manifest that is imported in the application.
         */
        LIBRARY
    }

    private final Element mRootElement;
    // this is initialized lazily to avoid un-necessary early parsing.
    @NonNull
    private final AtomicReference<XmlElement> mRootNode = new AtomicReference<XmlElement>(null);
    @NonNull
    private final SourceFile mSourceFile;
    @NonNull
    private final KeyResolver<String> mSelectors;
    @NonNull
    private final KeyBasedValueResolver<ManifestSystemProperty> mSystemPropertyResolver;
    @NonNull
    private final Type mType;
    @NonNull
    private final Optional<String> mMainManifestPackageName;

    public XmlDocument(
            @NonNull SourceFile sourceLocation,
            @NonNull KeyResolver<String> selectors,
            @NonNull KeyBasedValueResolver<ManifestSystemProperty> systemPropertyResolver,
            @NonNull Element element,
            @NonNull Type type,
            @NonNull Optional<String> mainManifestPackageName) {
        this.mSourceFile = Preconditions.checkNotNull(sourceLocation);
        this.mRootElement = Preconditions.checkNotNull(element);
        this.mSelectors = Preconditions.checkNotNull(selectors);
        this.mSystemPropertyResolver = Preconditions.checkNotNull(systemPropertyResolver);
        this.mType = type;
        this.mMainManifestPackageName = mainManifestPackageName;
    }

    @NonNull
    public Type getFileType() {
        return mType;
    }

    /**
     * Returns a pretty string representation of this document.
     */
    @NonNull
    public String prettyPrint() {
        return XmlPrettyPrinter.prettyPrint(
                getXml(),
                XmlFormatPreferences.defaults(),
                XmlFormatStyle.get(getRootNode().getXml()),
                null, /* endOfLineSeparator */
                false /* endWithNewLine */);
    }

    /**
     * merge this higher priority document with a higher priority document.
     * @param lowerPriorityDocument the lower priority document to merge in.
     * @param mergingReportBuilder the merging report to record errors and actions.
     * @return a new merged {@link com.android.manifmerger.XmlDocument} or
     * {@link Optional#absent()} if there were errors during the merging activities.
     */
    @NonNull
    public Optional<XmlDocument> merge(
            @NonNull XmlDocument lowerPriorityDocument,
            @NonNull MergingReport.Builder mergingReportBuilder) {

        if (getFileType() == Type.MAIN) {
            mergingReportBuilder.getActionRecorder().recordDefaultNodeAction(getRootNode());
        }

        getRootNode().mergeWithLowerPriorityNode(
                lowerPriorityDocument.getRootNode(), mergingReportBuilder);

        addImplicitElements(lowerPriorityDocument, mergingReportBuilder);

        // force re-parsing as new nodes may have appeared.
        return mergingReportBuilder.hasErrors()
                ? Optional.<XmlDocument>absent()
                : Optional.of(reparse());
    }

    /**
     * Forces a re-parsing of the document
     * @return a new {@link com.android.manifmerger.XmlDocument} with up to date information.
     */
    @NonNull
    public XmlDocument reparse() {
        return new XmlDocument(
                mSourceFile,
                mSelectors,
                mSystemPropertyResolver,
                mRootElement,
                mType,
                mMainManifestPackageName);
    }

    /**
     * Returns a {@link com.android.manifmerger.KeyResolver} capable of resolving all selectors
     * types
     */
    @NonNull
    public KeyResolver<String> getSelectors() {
        return mSelectors;
    }

    /**
     * Returns the {@link com.android.manifmerger.PlaceholderHandler.KeyBasedValueResolver} capable
     * of resolving all injected {@link ManifestSystemProperty}
     */
    @NonNull
    public KeyBasedValueResolver<ManifestSystemProperty> getSystemPropertyResolver() {
        return mSystemPropertyResolver;
    }

    /**
     * Compares this document to another {@link com.android.manifmerger.XmlDocument} ignoring all
     * attributes belonging to the {@link SdkConstants#TOOLS_URI} namespace.
     *
     * @param other the other document to compare against.
     * @return  a {@link String} describing the differences between the two XML elements or
     * {@link Optional#absent()} if they are equals.
     */
    @SuppressWarnings("CovariantCompareTo")
    public Optional<String> compareTo(@NonNull XmlDocument other) {
        return getRootNode().compareTo(other.getRootNode());
    }

    /**
     * Returns the position of the specified {@link XmlNode}.
     */
    @NonNull
    static SourcePosition getNodePosition(@NonNull XmlNode node) {
        return getNodePosition(node.getXml());
    }

    /**
     * Returns the position of the specified {@link Node}.
     */
    @NonNull
    static SourcePosition getNodePosition(@NonNull Node xml) {
        return PositionXmlParser.getPosition(xml);
    }

    /**
     * Returns the {@link SourceFile} associated with this XML document.
     * <p>
     * NOTE: You should <b>not</b> read the contents of the file directly; if you need to
     * access the content, use {@link ManifestMerger2#getFileStreamProvider()} instead.
     *
     * @return the source file
     */
    @NonNull
    public SourceFile getSourceFile() {
        return mSourceFile;
    }

    public synchronized XmlElement getRootNode() {
        if (mRootNode.get() == null) {
            this.mRootNode.set(new XmlElement(mRootElement, this));
        }
        return mRootNode.get();
    }

    public Optional<XmlElement> getByTypeAndKey(
            ManifestModel.NodeTypes type,
            @Nullable String keyValue) {

        return getRootNode().getNodeByTypeAndKey(type, keyValue);
    }

    /**
     * Package name for this android manifest which will be used to resolve
     * partial path. In the case of Overlays, this is absent and the main
     * manifest packageName must be used.
     * @return the package name to do partial class names resolution.
     */
    public String getPackageName() {
        return mMainManifestPackageName.or(mRootElement.getAttribute("package"));
    }

    /**
     * Returns the package name to use to expand the attributes values with the
     * document's package name
     * @return the package name to use for attribute expansion.
     */
    public String getPackageNameForAttributeExpansion() {
        String aPackage = mRootElement.getAttribute("package");
        if (aPackage != null) {
            return aPackage;
        }
        if (mMainManifestPackageName.isPresent()) {
            return mMainManifestPackageName.get();
        }
        throw new RuntimeException("No package present in overlay or main manifest file");
    }

    public Optional<XmlAttribute> getPackage() {
        Optional<XmlAttribute> packageAttribute =
                getRootNode().getAttribute(XmlNode.fromXmlName("package"));
        return packageAttribute.isPresent()
                ? packageAttribute
                : getRootNode().getAttribute(XmlNode.fromNSName(
                        SdkConstants.ANDROID_URI, "android", "package"));
    }

    public Document getXml() {
        return mRootElement.getOwnerDocument();
    }

    /**
     * Returns the minSdk version specified in the uses_sdk element if present or the
     * default value.
     */
    @NonNull
    private String getRawMinSdkVersion() {
        Optional<XmlElement> usesSdk = getByTypeAndKey(
                ManifestModel.NodeTypes.USES_SDK, null);
        if (usesSdk.isPresent()) {
            Optional<XmlAttribute> minSdkVersion = usesSdk.get()
                    .getAttribute(XmlNode.fromXmlName("android:minSdkVersion"));
            if (minSdkVersion.isPresent()) {
                return minSdkVersion.get().getValue();
            }
        }
        return DEFAULT_SDK_VERSION;
    }

    /**
     * Returns the minSdk version for this manifest file. It can be injected from the outer
     * build.gradle or can be expressed in the uses_sdk element.
     */
    @NonNull
    private String getMinSdkVersion() {
        // check for system properties.
        String injectedMinSdk = mSystemPropertyResolver.getValue(ManifestSystemProperty.MIN_SDK_VERSION);
        if (injectedMinSdk != null) {
            return injectedMinSdk;
        }
        return getRawMinSdkVersion();
    }

    /**
     * Returns the targetSdk version specified in the uses_sdk element if present or the
     * default value.
     */
    @NonNull
    private String getRawTargetSdkVersion() {

        Optional<XmlElement> usesSdk = getByTypeAndKey(
                ManifestModel.NodeTypes.USES_SDK, null);
        if (usesSdk.isPresent()) {
            Optional<XmlAttribute> targetSdkVersion = usesSdk.get()
                    .getAttribute(XmlNode.fromXmlName("android:targetSdkVersion"));
            if (targetSdkVersion.isPresent()) {
                return targetSdkVersion.get().getValue();
            }
        }
        return getRawMinSdkVersion();
    }

    /**
     * Returns the targetSdk version for this manifest file. It can be injected from the outer
     * build.gradle or can be expressed in the uses_sdk element.
     */
    @NonNull
    private String getTargetSdkVersion() {

        // check for system properties.
        String injectedTargetVersion = mSystemPropertyResolver
                .getValue(ManifestSystemProperty.TARGET_SDK_VERSION);
        if (injectedTargetVersion != null) {
            return injectedTargetVersion;
        }
        return getRawTargetSdkVersion();
    }

    /**
     * Decodes a sdk version from either its decimal representation or from a platform code name.
     * @param attributeVersion the sdk version attribute as specified by users.
     * @return the integer representation of the platform level.
     */
    private static int getApiLevelFromAttribute(@NonNull String attributeVersion) {
        Preconditions.checkArgument(!Strings.isNullOrEmpty(attributeVersion));
        if (Character.isDigit(attributeVersion.charAt(0))) {
            return Integer.parseInt(attributeVersion);
        }
        return SdkVersionInfo.getApiByPreviewName(attributeVersion, true);
    }

    /**
     * Add all implicit elements from the passed lower priority document that are
     * required in the target SDK.
     */
    @SuppressWarnings("unchecked") // compiler confused about varargs and generics.
    private void addImplicitElements(@NonNull XmlDocument lowerPriorityDocument,
            @NonNull MergingReport.Builder mergingReport) {

        // if this document is an overlay, tolerate the absence of uses-sdk and do not
        // assume implicit minimum versions.
        Optional<XmlElement> usesSdk = getByTypeAndKey(
                ManifestModel.NodeTypes.USES_SDK, null);
        if (mType == Type.OVERLAY && !usesSdk.isPresent()) {
            return;
        }

        // check that the uses-sdk element does not have any tools:node instruction.
        if (usesSdk.isPresent()) {
            XmlElement usesSdkElement = usesSdk.get();
            if (usesSdkElement.getOperationType() != NodeOperationType.MERGE) {
                mergingReport
                        .addMessage(
                                new SourceFilePosition(
                                        getSourceFile(),
                                        usesSdkElement.getPosition()),
                                MergingReport.Record.Severity.ERROR,
                                "uses-sdk element cannot have a \"tools:node\" attribute");
                return;
            }
        }
        int thisTargetSdk = getApiLevelFromAttribute(getTargetSdkVersion());

        // when we are importing a library, we should never use the build.gradle injected
        // values (only valid for overlay, main manifest) so use the raw versions coming from
        // the AndroidManifest.xml
        int libraryTargetSdk = getApiLevelFromAttribute(
                lowerPriorityDocument.getFileType() == Type.LIBRARY
                    ? lowerPriorityDocument.getRawTargetSdkVersion()
                    : lowerPriorityDocument.getTargetSdkVersion());

        // if library is using a code name rather than an API level, make sure this document target
        // sdk version is using the same code name.
        String libraryTargetSdkVersion = lowerPriorityDocument.getTargetSdkVersion();
        if (!Character.isDigit(libraryTargetSdkVersion.charAt(0))) {
            // this is a code name, ensure this document uses the same code name.
            if (!libraryTargetSdkVersion.equals(getTargetSdkVersion())) {
                mergingReport.addMessage(getSourceFile(), MergingReport.Record.Severity.ERROR,
                        String.format(
                                "uses-sdk:targetSdkVersion %1$s cannot be different than version "
                                        + "%2$s declared in library %3$s",
                                getTargetSdkVersion(),
                                libraryTargetSdkVersion,
                                lowerPriorityDocument.getSourceFile().print(false)
                        )
                );
                return;
            }
        }
        // same for minSdkVersion, if the library is using a code name, the application must
        // also be using the same code name.
        String libraryMinSdkVersion = lowerPriorityDocument.getRawMinSdkVersion();
        if (!Character.isDigit(libraryMinSdkVersion.charAt(0))) {
            // this is a code name, ensure this document uses the same code name.
            if (!libraryMinSdkVersion.equals(getMinSdkVersion())) {
                mergingReport.addMessage(getSourceFile(), MergingReport.Record.Severity.ERROR,
                        String.format(
                                "uses-sdk:minSdkVersion %1$s cannot be different than version "
                                        + "%2$s declared in library %3$s",
                                getMinSdkVersion(),
                                libraryMinSdkVersion,
                                lowerPriorityDocument.getSourceFile().print(false)
                        )
                );
                return;
            }
        }

        if (!checkUsesSdkMinVersion(lowerPriorityDocument, mergingReport)) {
            String error = String.format(
                            "uses-sdk:minSdkVersion %1$s cannot be smaller than version "
                                    + "%2$s declared in library %3$s\n"
                                    + "\tSuggestion: use tools:overrideLibrary=\"%4$s\" to force usage",
                            getMinSdkVersion(),
                            lowerPriorityDocument.getRawMinSdkVersion(),
                            lowerPriorityDocument.getSourceFile().print(false),
                            lowerPriorityDocument.getPackageName());
            if (usesSdk.isPresent()) {
                mergingReport.addMessage(
                        new SourceFilePosition(getSourceFile(), usesSdk.get().getPosition()),
                        MergingReport.Record.Severity.ERROR,
                        error);
            } else {
                mergingReport.addMessage(
                        getSourceFile(), MergingReport.Record.Severity.ERROR, error);
            }
            return;
        }

        // if the merged document target SDK is equal or smaller than the library's, nothing to do.
        if (thisTargetSdk <= libraryTargetSdk) {
            return;
        }

        // There is no need to add any implied permissions when targeting an old runtime.
        if (thisTargetSdk < 4) {
            return;
        }

        boolean hasWriteToExternalStoragePermission =
                lowerPriorityDocument.getByTypeAndKey(
                        USES_PERMISSION, permission("WRITE_EXTERNAL_STORAGE")).isPresent();

        if (libraryTargetSdk < 4) {
            addIfAbsent(mergingReport.getActionRecorder(),
                    USES_PERMISSION,
                    permission("WRITE_EXTERNAL_STORAGE"),
                    lowerPriorityDocument.getPackageName() + " has a targetSdkVersion < 4");
            hasWriteToExternalStoragePermission = true;

            addIfAbsent(mergingReport.getActionRecorder(),
                    USES_PERMISSION,
                    permission("READ_PHONE_STATE"),
                    lowerPriorityDocument.getPackageName() + " has a targetSdkVersion < 4");
        }

        // If the application has requested WRITE_EXTERNAL_STORAGE, we will
        // force them to always take READ_EXTERNAL_STORAGE as well.  We always
        // do this (regardless of target API version) because we can't have
        // an app with write permission but not read permission.
        if (hasWriteToExternalStoragePermission) {

            addIfAbsent(mergingReport.getActionRecorder(),
                    USES_PERMISSION,
                    permission("READ_EXTERNAL_STORAGE"),
                    lowerPriorityDocument.getPackageName() + " requested WRITE_EXTERNAL_STORAGE");
        }

        // Pre-JellyBean call log permission compatibility.
        if (thisTargetSdk >= 16 && libraryTargetSdk < 16) {
            if (lowerPriorityDocument.getByTypeAndKey(
                    USES_PERMISSION, permission("READ_CONTACTS")).isPresent()) {
                addIfAbsent(mergingReport.getActionRecorder(),
                        USES_PERMISSION, permission("READ_CALL_LOG"),
                        lowerPriorityDocument.getPackageName()
                                + " has targetSdkVersion < 16 and requested READ_CONTACTS");
            }
            if (lowerPriorityDocument.getByTypeAndKey(
                    USES_PERMISSION, permission("WRITE_CONTACTS")).isPresent()) {
                addIfAbsent(mergingReport.getActionRecorder(),
                        USES_PERMISSION, permission("WRITE_CALL_LOG"),
                        lowerPriorityDocument.getPackageName()
                                + " has targetSdkVersion < 16 and requested WRITE_CONTACTS");
            }
        }
    }

    /**
     * Returns true if the minSdkVersion of the application and the library are compatible, false
     * otherwise.
     */
    private boolean checkUsesSdkMinVersion(@NonNull XmlDocument lowerPriorityDocument,
            MergingReport.Builder mergingReport) {

        int thisMinSdk = getApiLevelFromAttribute(getMinSdkVersion());
        int libraryMinSdk = getApiLevelFromAttribute(
                lowerPriorityDocument.getRawMinSdkVersion());

        // the merged document minSdk cannot be lower than a library
        if (thisMinSdk < libraryMinSdk) {

            // check if this higher priority document has any tools instructions for the node
            Optional<XmlElement> xmlElementOptional = getByTypeAndKey(USES_SDK, null);
            if (!xmlElementOptional.isPresent()) {
                return false;
            }
            XmlElement xmlElement = xmlElementOptional.get();

            // if we find a selector that applies to this library. the users wants to explicitly
            // allow this higher version library to be allowed.
            for (Selector selector : xmlElement.getOverrideUsesSdkLibrarySelectors()) {
                if (selector.appliesTo(lowerPriorityDocument.getRootNode())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /**
     * Adds a new element of type nodeType with a specific keyValue if the element is absent in this
     * document. Will also add attributes expressed through key value pairs.
     *
     * @param actionRecorder to records creation actions.
     * @param nodeType the node type to crete
     * @param keyValue the optional key for the element.
     * @param attributes the optional array of key value pairs for extra element attribute.
     * @return the Xml element whether it was created or existed or {@link Optional#absent()} if
     * it does not exist in this document.
     */
    private Optional<Element> addIfAbsent(
            @NonNull ActionRecorder actionRecorder,
            @NonNull ManifestModel.NodeTypes nodeType,
            @Nullable String keyValue,
            @Nullable String reason,
            @Nullable Pair<String, String>... attributes) {

        Optional<XmlElement> xmlElementOptional = getByTypeAndKey(nodeType, keyValue);
        if (xmlElementOptional.isPresent()) {
            return Optional.absent();
        }
        Element elementNS = getXml()
                .createElementNS(SdkConstants.ANDROID_URI, "android:" + nodeType.toXmlName());


        ImmutableList<String> keyAttributesNames = nodeType.getNodeKeyResolver()
                .getKeyAttributesNames();
        if (keyAttributesNames.size() == 1) {
            elementNS.setAttributeNS(
                    SdkConstants.ANDROID_URI, "android:" + keyAttributesNames.get(0), keyValue);
        }
        if (attributes != null) {
            for (Pair<String, String> attribute : attributes) {
                elementNS.setAttributeNS(
                        SdkConstants.ANDROID_URI, "android:" + attribute.getFirst(),
                        attribute.getSecond());
            }
        }

        // record creation.
        XmlElement xmlElement = new XmlElement(elementNS, this);
        actionRecorder.recordImpliedNodeAction(xmlElement, reason);

        getRootNode().getXml().appendChild(elementNS);
        return Optional.of(elementNS);
    }

    @NonNull
    private static String permission(String permissionName) {
        return "android.permission." + permissionName;
    }
}
