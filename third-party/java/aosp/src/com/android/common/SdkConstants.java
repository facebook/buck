/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.common;

import java.io.File;

/**
 * Constant definition class.<br>
 * <br>
 * Most constants have a prefix defining the content.
 * <ul>
 * <li><code>OS_</code> OS path constant. These paths are different depending on the platform.</li>
 * <li><code>FN_</code> File name constant.</li>
 * <li><code>FD_</code> Folder name constant.</li>
 * <li><code>TAG_</code> XML element tag name</li>
 * <li><code>ATTR_</code> XML attribute name</li>
 * <li><code>VALUE_</code> XML attribute value</li>
 * <li><code>CLASS_</code> Class name</li>
 * <li><code>DOT_</code> File name extension, including the dot </li>
 * <li><code>EXT_</code> File name extension, without the dot </li>
 * </ul>
 */
@SuppressWarnings("javadoc") // Not documenting all the fields here
public final class SdkConstants {
    public static final int PLATFORM_UNKNOWN = 0;
    public static final int PLATFORM_LINUX = 1;
    public static final int PLATFORM_WINDOWS = 2;
    public static final int PLATFORM_DARWIN = 3;

    /**
     * Returns current platform, one of {@link #PLATFORM_WINDOWS}, {@link #PLATFORM_DARWIN},
     * {@link #PLATFORM_LINUX} or {@link #PLATFORM_UNKNOWN}.
     */
    public static final int CURRENT_PLATFORM = currentPlatform();

    /**
     * Charset for the ini file handled by the SDK.
     */
    public static final String INI_CHARSET = "UTF-8";                                 //$NON-NLS-1$

    /** An SDK Project's AndroidManifest.xml file */
    public static final String FN_ANDROID_MANIFEST_XML= "AndroidManifest.xml";        //$NON-NLS-1$
    /** pre-dex jar filename. i.e. "classes.jar" */
    public static final String FN_CLASSES_JAR = "classes.jar";                        //$NON-NLS-1$
    /** Dex filename inside the APK. i.e. "classes.dex" */
    public static final String FN_APK_CLASSES_DEX = "classes.dex";                    //$NON-NLS-1$

    /** An SDK Project's build.xml file */
    public static final String FN_BUILD_XML = "build.xml";                            //$NON-NLS-1$

    /** Name of the framework library, i.e. "android.jar" */
    public static final String FN_FRAMEWORK_LIBRARY = "android.jar";                  //$NON-NLS-1$
    /** Name of the framework library, i.e. "uiautomator.jar" */
    public static final String FN_UI_AUTOMATOR_LIBRARY = "uiautomator.jar";           //$NON-NLS-1$
    /** Name of the layout attributes, i.e. "attrs.xml" */
    public static final String FN_ATTRS_XML = "attrs.xml";                            //$NON-NLS-1$
    /** Name of the layout attributes, i.e. "attrs_manifest.xml" */
    public static final String FN_ATTRS_MANIFEST_XML = "attrs_manifest.xml";          //$NON-NLS-1$
    /** framework aidl import file */
    public static final String FN_FRAMEWORK_AIDL = "framework.aidl";                  //$NON-NLS-1$
    /** framework renderscript folder */
    public static final String FN_FRAMEWORK_RENDERSCRIPT = "renderscript";            //$NON-NLS-1$
    /** framework include folder */
    public static final String FN_FRAMEWORK_INCLUDE = "include";                      //$NON-NLS-1$
    /** framework include (clang) folder */
    public static final String FN_FRAMEWORK_INCLUDE_CLANG = "clang-include";          //$NON-NLS-1$
    /** layoutlib.jar file */
    public static final String FN_LAYOUTLIB_JAR = "layoutlib.jar";                    //$NON-NLS-1$
    /** widget list file */
    public static final String FN_WIDGETS = "widgets.txt";                            //$NON-NLS-1$
    /** Intent activity actions list file */
    public static final String FN_INTENT_ACTIONS_ACTIVITY = "activity_actions.txt";   //$NON-NLS-1$
    /** Intent broadcast actions list file */
    public static final String FN_INTENT_ACTIONS_BROADCAST = "broadcast_actions.txt"; //$NON-NLS-1$
    /** Intent service actions list file */
    public static final String FN_INTENT_ACTIONS_SERVICE = "service_actions.txt";     //$NON-NLS-1$
    /** Intent category list file */
    public static final String FN_INTENT_CATEGORIES = "categories.txt";               //$NON-NLS-1$

    /** annotations support jar */
    public static final String FN_ANNOTATIONS_JAR = "annotations.jar";                //$NON-NLS-1$

    /** platform build property file */
    public static final String FN_BUILD_PROP = "build.prop";                          //$NON-NLS-1$
    /** plugin properties file */
    public static final String FN_PLUGIN_PROP = "plugin.prop";                        //$NON-NLS-1$
    /** add-on manifest file */
    public static final String FN_MANIFEST_INI = "manifest.ini";                      //$NON-NLS-1$
    /** add-on layout device XML file. */
    public static final String FN_DEVICES_XML = "devices.xml";                        //$NON-NLS-1$
    /** hardware properties definition file */
    public static final String FN_HARDWARE_INI = "hardware-properties.ini";           //$NON-NLS-1$

    /** project property file */
    public static final String FN_PROJECT_PROPERTIES = "project.properties";          //$NON-NLS-1$

    /** project local property file */
    public static final String FN_LOCAL_PROPERTIES = "local.properties";              //$NON-NLS-1$

    /** project ant property file */
    public static final String FN_ANT_PROPERTIES = "ant.properties";                  //$NON-NLS-1$

    /** Skin layout file */
    public static final String FN_SKIN_LAYOUT = "layout";                             //$NON-NLS-1$

    /** dx.jar file */
    public static final String FN_DX_JAR = "dx.jar";                                  //$NON-NLS-1$

    /** dx executable (with extension for the current OS) */
    public static final String FN_DX =
        "dx" + ext(".bat", "");                           //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** aapt executable (with extension for the current OS) */
    public static final String FN_AAPT =
        "aapt" + ext(".exe", "");                         //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** aidl executable (with extension for the current OS) */
    public static final String FN_AIDL =
        "aidl" + ext(".exe", "");                         //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** renderscript executable (with extension for the current OS) */
    public static final String FN_RENDERSCRIPT =
        "llvm-rs-cc" + ext(".exe", "");                   //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** adb executable (with extension for the current OS) */
    public static final String FN_ADB =
        "adb" + ext(".exe", "");                          //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** emulator executable for the current OS */
    public static final String FN_EMULATOR =
        "emulator" + ext(".exe", "");                     //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** zipalign executable (with extension for the current OS) */
    public static final String FN_ZIPALIGN =
        "zipalign" + ext(".exe", "");                     //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** dexdump executable (with extension for the current OS) */
    public static final String FN_DEXDUMP =
        "dexdump" + ext(".exe", "");                      //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** proguard executable (with extension for the current OS) */
    public static final String FN_PROGUARD =
        "proguard" + ext(".bat", ".sh");                  //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** find_lock for Windows (with extension for the current OS) */
    public static final String FN_FIND_LOCK =
        "find_lock" + ext(".exe", "");                    //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

    /** properties file for SDK Updater packages */
    public static final String FN_SOURCE_PROP = "source.properties";                  //$NON-NLS-1$
    /** properties file for content hash of installed packages */
    public static final String FN_CONTENT_HASH_PROP = "content_hash.properties";      //$NON-NLS-1$
    /** properties file for the SDK */
    public static final String FN_SDK_PROP = "sdk.properties";                        //$NON-NLS-1$

    /**
     * filename for gdbserver.
     */
    public static final String FN_GDBSERVER = "gdbserver";              //$NON-NLS-1$

    /** global Android proguard config file */
    public static final String FN_ANDROID_PROGUARD_FILE = "proguard-android.txt";   //$NON-NLS-1$
    /** global Android proguard config file with optimization enabled */
    public static final String FN_ANDROID_OPT_PROGUARD_FILE = "proguard-android-optimize.txt";  //$NON-NLS-1$
    /** default proguard config file with new file extension (for project specific stuff) */
    public static final String FN_PROJECT_PROGUARD_FILE = "proguard-project.txt";   //$NON-NLS-1$

    /* Folder Names for Android Projects . */

    /** Resources folder name, i.e. "res". */
    public static final String FD_RESOURCES = "res";                    //$NON-NLS-1$
    /** Assets folder name, i.e. "assets" */
    public static final String FD_ASSETS = "assets";                    //$NON-NLS-1$
    /** Default source folder name in an SDK project, i.e. "src".
     * <p/>
     * Note: this is not the same as {@link #FD_PKG_SOURCES}
     * which is an SDK sources folder for packages. */
    public static final String FD_SOURCES = "src";                      //$NON-NLS-1$
    /** Default generated source folder name, i.e. "gen" */
    public static final String FD_GEN_SOURCES = "gen";                  //$NON-NLS-1$
    /** Default native library folder name inside the project, i.e. "libs"
     * While the folder inside the .apk is "lib", we call that one libs because
     * that's what we use in ant for both .jar and .so and we need to make the 2 development ways
     * compatible. */
    public static final String FD_NATIVE_LIBS = "libs";                 //$NON-NLS-1$
    /** Native lib folder inside the APK: "lib" */
    public static final String FD_APK_NATIVE_LIBS = "lib";              //$NON-NLS-1$
    /** Default output folder name, i.e. "bin" */
    public static final String FD_OUTPUT = "bin";                       //$NON-NLS-1$
    /** Classes output folder name, i.e. "classes" */
    public static final String FD_CLASSES_OUTPUT = "classes";           //$NON-NLS-1$
    /** proguard output folder for mapping, etc.. files */
    public static final String FD_PROGUARD = "proguard";                //$NON-NLS-1$
    /** aidl output folder for copied aidl files */
    public static final String FD_AIDL = "aidl";                        //$NON-NLS-1$

    /* Folder Names for the Android SDK */

    /** Name of the SDK platforms folder. */
    public static final String FD_PLATFORMS = "platforms";              //$NON-NLS-1$
    /** Name of the SDK addons folder. */
    public static final String FD_ADDONS = "add-ons";                   //$NON-NLS-1$
    /** Name of the SDK system-images folder. */
    public static final String FD_SYSTEM_IMAGES = "system-images";      //$NON-NLS-1$
    /** Name of the SDK sources folder where source packages are installed.
     * <p/>
     * Note this is not the same as {@link #FD_SOURCES} which is the folder name where sources
     * are installed inside a project. */
    public static final String FD_PKG_SOURCES = "sources";              //$NON-NLS-1$
    /** Name of the SDK tools folder. */
    public static final String FD_TOOLS = "tools";                      //$NON-NLS-1$
    /** Name of the SDK tools/support folder. */
    public static final String FD_SUPPORT = "support";                  //$NON-NLS-1$
    /** Name of the SDK platform tools folder. */
    public static final String FD_PLATFORM_TOOLS = "platform-tools";    //$NON-NLS-1$
    /** Name of the SDK tools/lib folder. */
    public static final String FD_LIB = "lib";                          //$NON-NLS-1$
    /** Name of the SDK docs folder. */
    public static final String FD_DOCS = "docs";                        //$NON-NLS-1$
    /** Name of the doc folder containing API reference doc (javadoc) */
    public static final String FD_DOCS_REFERENCE = "reference";         //$NON-NLS-1$
    /** Name of the SDK images folder. */
    public static final String FD_IMAGES = "images";                    //$NON-NLS-1$
    /** Name of the ABI to support. */
    public static final String ABI_ARMEABI = "armeabi";                 //$NON-NLS-1$
    public static final String ABI_ARMEABI_V7A = "armeabi-v7a";         //$NON-NLS-1$
    public static final String ABI_INTEL_ATOM = "x86";                  //$NON-NLS-1$
    public static final String ABI_MIPS = "mips";                       //$NON-NLS-1$
    /** Name of the CPU arch to support. */
    public static final String CPU_ARCH_ARM = "arm";                    //$NON-NLS-1$
    public static final String CPU_ARCH_INTEL_ATOM = "x86";             //$NON-NLS-1$
    public static final String CPU_ARCH_MIPS = "mips";                  //$NON-NLS-1$
    /** Name of the CPU model to support. */
    public static final String CPU_MODEL_CORTEX_A8 = "cortex-a8";       //$NON-NLS-1$

    /** Name of the SDK skins folder. */
    public static final String FD_SKINS = "skins";                      //$NON-NLS-1$
    /** Name of the SDK samples folder. */
    public static final String FD_SAMPLES = "samples";                  //$NON-NLS-1$
    /** Name of the SDK extras folder. */
    public static final String FD_EXTRAS = "extras";                    //$NON-NLS-1$
    /**
     * Name of an extra's sample folder.
     * Ideally extras should have one {@link #FD_SAMPLES} folder containing
     * one or more sub-folders (one per sample). However some older extras
     * might contain a single "sample" folder with directly the samples files
     * in it. When possible we should encourage extras' owners to move to the
     * multi-samples format.
     */
    public static final String FD_SAMPLE = "sample";                    //$NON-NLS-1$
    /** Name of the SDK templates folder, i.e. "templates" */
    public static final String FD_TEMPLATES = "templates";              //$NON-NLS-1$
    /** Name of the SDK Ant folder, i.e. "ant" */
    public static final String FD_ANT = "ant";                          //$NON-NLS-1$
    /** Name of the SDK data folder, i.e. "data" */
    public static final String FD_DATA = "data";                        //$NON-NLS-1$
    /** Name of the SDK renderscript folder, i.e. "rs" */
    public static final String FD_RENDERSCRIPT = "rs";                  //$NON-NLS-1$
    /** Name of the SDK resources folder, i.e. "res" */
    public static final String FD_RES = "res";                          //$NON-NLS-1$
    /** Name of the SDK font folder, i.e. "fonts" */
    public static final String FD_FONTS = "fonts";                      //$NON-NLS-1$
    /** Name of the android sources directory */
    public static final String FD_ANDROID_SOURCES = "sources";          //$NON-NLS-1$
    /** Name of the addon libs folder. */
    public static final String FD_ADDON_LIBS = "libs";                  //$NON-NLS-1$

    /** Name of the cache folder in the $HOME/.android. */
    public static final String FD_CACHE = "cache";                      //$NON-NLS-1$

    /** API codename of a release (non preview) system image or platform. **/
    public static final String CODENAME_RELEASE = "REL";                //$NON-NLS-1$

    /** Namespace for the resource XML, i.e. "http://schemas.android.com/apk/res/android" */
    public static final String NS_RESOURCES =
        "http://schemas.android.com/apk/res/android";                   //$NON-NLS-1$

    /** Namespace for the device schema, i.e. "http://schemas.android.com/sdk/devices/1" */
    public static final String NS_DEVICES_XSD =
        "http://schemas.android.com/sdk/devices/1";                     //$NON-NLS-1$


    /** The name of the uses-library that provides "android.test.runner" */
    public static final String ANDROID_TEST_RUNNER_LIB =
        "android.test.runner";                                          //$NON-NLS-1$

    /* Folder path relative to the SDK root */
    /** Path of the documentation directory relative to the sdk folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SDK_DOCS_FOLDER = FD_DOCS + File.separator;

    /** Path of the tools directory relative to the sdk folder, or to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SDK_TOOLS_FOLDER = FD_TOOLS + File.separator;

    /** Path of the lib directory relative to the sdk folder, or to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SDK_TOOLS_LIB_FOLDER =
            OS_SDK_TOOLS_FOLDER + FD_LIB + File.separator;

    /**
     * Path of the lib directory relative to the sdk folder, or to a platform
     * folder. This is an OS path, ending with a separator.
     */
    public static final String OS_SDK_TOOLS_LIB_EMULATOR_FOLDER = OS_SDK_TOOLS_LIB_FOLDER
            + "emulator" + File.separator;                              //$NON-NLS-1$

    /** Path of the platform tools directory relative to the sdk folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SDK_PLATFORM_TOOLS_FOLDER = FD_PLATFORM_TOOLS + File.separator;

    /** Path of the Platform tools Lib directory relative to the sdk folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SDK_PLATFORM_TOOLS_LIB_FOLDER =
            OS_SDK_PLATFORM_TOOLS_FOLDER + FD_LIB + File.separator;

    /** Path of the bin folder of proguard folder relative to the sdk folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SDK_TOOLS_PROGUARD_BIN_FOLDER =
        SdkConstants.OS_SDK_TOOLS_FOLDER +
        "proguard" + File.separator +                                   //$NON-NLS-1$
        "bin" + File.separator;                                         //$NON-NLS-1$

    /* Folder paths relative to a platform or add-on folder */

    /** Path of the images directory relative to a platform or addon folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_IMAGES_FOLDER = FD_IMAGES + File.separator;

    /** Path of the skin directory relative to a platform or addon folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_SKINS_FOLDER = FD_SKINS + File.separator;

    /* Folder paths relative to a Platform folder */

    /** Path of the data directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_DATA_FOLDER = FD_DATA + File.separator;

    /** Path of the renderscript directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_RENDERSCRIPT_FOLDER = FD_RENDERSCRIPT + File.separator;


    /** Path of the samples directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_SAMPLES_FOLDER = FD_SAMPLES + File.separator;

    /** Path of the resources directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_RESOURCES_FOLDER =
            OS_PLATFORM_DATA_FOLDER + FD_RES + File.separator;

    /** Path of the fonts directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_FONTS_FOLDER =
            OS_PLATFORM_DATA_FOLDER + FD_FONTS + File.separator;

    /** Path of the android source directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_SOURCES_FOLDER = FD_ANDROID_SOURCES + File.separator;

    /** Path of the android templates directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_TEMPLATES_FOLDER = FD_TEMPLATES + File.separator;

    /** Path of the Ant build rules directory relative to a platform folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_PLATFORM_ANT_FOLDER = FD_ANT + File.separator;

    /** Path of the attrs.xml file relative to a platform folder. */
    public static final String OS_PLATFORM_ATTRS_XML =
            OS_PLATFORM_RESOURCES_FOLDER + SdkConstants.FD_RES_VALUES + File.separator +
            FN_ATTRS_XML;

    /** Path of the attrs_manifest.xml file relative to a platform folder. */
    public static final String OS_PLATFORM_ATTRS_MANIFEST_XML =
            OS_PLATFORM_RESOURCES_FOLDER + SdkConstants.FD_RES_VALUES + File.separator +
            FN_ATTRS_MANIFEST_XML;

    /** Path of the layoutlib.jar file relative to a platform folder. */
    public static final String OS_PLATFORM_LAYOUTLIB_JAR =
            OS_PLATFORM_DATA_FOLDER + FN_LAYOUTLIB_JAR;

    /** Path of the renderscript include folder relative to a platform folder. */
    public static final String OS_FRAMEWORK_RS =
            FN_FRAMEWORK_RENDERSCRIPT + File.separator + FN_FRAMEWORK_INCLUDE;
    /** Path of the renderscript (clang) include folder relative to a platform folder. */
    public static final String OS_FRAMEWORK_RS_CLANG =
            FN_FRAMEWORK_RENDERSCRIPT + File.separator + FN_FRAMEWORK_INCLUDE_CLANG;

    /* Folder paths relative to a addon folder */
    /** Path of the images directory relative to a folder folder.
     *  This is an OS path, ending with a separator. */
    public static final String OS_ADDON_LIBS_FOLDER = FD_ADDON_LIBS + File.separator;

    /** Skin default **/
    public static final String SKIN_DEFAULT = "default";                    //$NON-NLS-1$

    /** SDK property: ant templates revision */
    public static final String PROP_SDK_ANT_TEMPLATES_REVISION =
        "sdk.ant.templates.revision";                                       //$NON-NLS-1$

    /** SDK property: default skin */
    public static final String PROP_SDK_DEFAULT_SKIN = "sdk.skin.default"; //$NON-NLS-1$

    /* Android Class Constants */
    public static final String CLASS_ACTIVITY = "android.app.Activity"; //$NON-NLS-1$
    public static final String CLASS_APPLICATION = "android.app.Application"; //$NON-NLS-1$
    public static final String CLASS_SERVICE = "android.app.Service"; //$NON-NLS-1$
    public static final String CLASS_BROADCASTRECEIVER = "android.content.BroadcastReceiver"; //$NON-NLS-1$
    public static final String CLASS_CONTENTPROVIDER = "android.content.ContentProvider"; //$NON-NLS-1$
    public static final String CLASS_INSTRUMENTATION = "android.app.Instrumentation"; //$NON-NLS-1$
    public static final String CLASS_INSTRUMENTATION_RUNNER =
        "android.test.InstrumentationTestRunner"; //$NON-NLS-1$
    public static final String CLASS_BUNDLE = "android.os.Bundle"; //$NON-NLS-1$
    public static final String CLASS_R = "android.R"; //$NON-NLS-1$
    public static final String CLASS_MANIFEST_PERMISSION = "android.Manifest$permission"; //$NON-NLS-1$
    public static final String CLASS_INTENT = "android.content.Intent"; //$NON-NLS-1$
    public static final String CLASS_CONTEXT = "android.content.Context"; //$NON-NLS-1$
    public static final String CLASS_VIEW = "android.view.View"; //$NON-NLS-1$
    public static final String CLASS_VIEWGROUP = "android.view.ViewGroup"; //$NON-NLS-1$
    public static final String CLASS_NAME_LAYOUTPARAMS = "LayoutParams"; //$NON-NLS-1$
    public static final String CLASS_VIEWGROUP_LAYOUTPARAMS =
        CLASS_VIEWGROUP + "$" + CLASS_NAME_LAYOUTPARAMS; //$NON-NLS-1$
    public static final String CLASS_NAME_FRAMELAYOUT = "FrameLayout"; //$NON-NLS-1$
    public static final String CLASS_FRAMELAYOUT =
        "android.widget." + CLASS_NAME_FRAMELAYOUT; //$NON-NLS-1$
    public static final String CLASS_PREFERENCE = "android.preference.Preference"; //$NON-NLS-1$
    public static final String CLASS_NAME_PREFERENCE_SCREEN = "PreferenceScreen"; //$NON-NLS-1$
    public static final String CLASS_PREFERENCES =
        "android.preference." + CLASS_NAME_PREFERENCE_SCREEN; //$NON-NLS-1$
    public static final String CLASS_PREFERENCEGROUP = "android.preference.PreferenceGroup"; //$NON-NLS-1$
    public static final String CLASS_PARCELABLE = "android.os.Parcelable"; //$NON-NLS-1$
    public static final String CLASS_FRAGMENT = "android.app.Fragment"; //$NON-NLS-1$
    public static final String CLASS_V4_FRAGMENT = "android.support.v4.app.Fragment"; //$NON-NLS-1$
    /** MockView is part of the layoutlib bridge and used to display classes that have
     * no rendering in the graphical layout editor. */
    public static final String CLASS_MOCK_VIEW = "com.android.layoutlib.bridge.MockView"; //$NON-NLS-1$

    /** Returns the appropriate name for the 'android' command, which is 'android.exe' for
     * Windows and 'android' for all other platforms. */
    public static String androidCmdName() {
        String os = System.getProperty("os.name");          //$NON-NLS-1$
        String cmd = "android";                             //$NON-NLS-1$
        if (os.startsWith("Windows")) {                     //$NON-NLS-1$
            cmd += ".bat";                                  //$NON-NLS-1$
        }
        return cmd;
    }

    /** Returns the appropriate name for the 'mksdcard' command, which is 'mksdcard.exe' for
     * Windows and 'mkdsdcard' for all other platforms. */
    public static String mkSdCardCmdName() {
        String os = System.getProperty("os.name");          //$NON-NLS-1$
        String cmd = "mksdcard";                            //$NON-NLS-1$
        if (os.startsWith("Windows")) {                     //$NON-NLS-1$
            cmd += ".exe";                                  //$NON-NLS-1$
        }
        return cmd;
    }

    /**
     * Returns current platform
     *
     * @return one of {@link #PLATFORM_WINDOWS}, {@link #PLATFORM_DARWIN},
     * {@link #PLATFORM_LINUX} or {@link #PLATFORM_UNKNOWN}.
     */
    public static int currentPlatform() {
        String os = System.getProperty("os.name");          //$NON-NLS-1$
        if (os.startsWith("Mac OS")) {                      //$NON-NLS-1$
            return PLATFORM_DARWIN;
        } else if (os.startsWith("Windows")) {              //$NON-NLS-1$
            return PLATFORM_WINDOWS;
        } else if (os.startsWith("Linux")) {                //$NON-NLS-1$
            return PLATFORM_LINUX;
        }

        return PLATFORM_UNKNOWN;
    }

    /**
     * Returns current platform's UI name
     *
     * @return one of "Windows", "Mac OS X", "Linux" or "other".
     */
    public static String currentPlatformName() {
        String os = System.getProperty("os.name");          //$NON-NLS-1$
        if (os.startsWith("Mac OS")) {                      //$NON-NLS-1$
            return "Mac OS X";                              //$NON-NLS-1$
        } else if (os.startsWith("Windows")) {              //$NON-NLS-1$
            return "Windows";                               //$NON-NLS-1$
        } else if (os.startsWith("Linux")) {                //$NON-NLS-1$
            return "Linux";                                 //$NON-NLS-1$
        }

        return "Other";
    }

    private static String ext(String windowsExtension, String nonWindowsExtension) {
        if (CURRENT_PLATFORM == PLATFORM_WINDOWS) {
            return windowsExtension;
        } else {
            return nonWindowsExtension;
        }
    }

    /** Default anim resource folder name, i.e. "anim" */
    public static final String FD_RES_ANIM = "anim"; //$NON-NLS-1$
    /** Default animator resource folder name, i.e. "animator" */
    public static final String FD_RES_ANIMATOR = "animator"; //$NON-NLS-1$
    /** Default color resource folder name, i.e. "color" */
    public static final String FD_RES_COLOR = "color"; //$NON-NLS-1$
    /** Default drawable resource folder name, i.e. "drawable" */
    public static final String FD_RES_DRAWABLE = "drawable"; //$NON-NLS-1$
    /** Default interpolator resource folder name, i.e. "interpolator" */
    public static final String FD_RES_INTERPOLATOR = "interpolator"; //$NON-NLS-1$
    /** Default layout resource folder name, i.e. "layout" */
    public static final String FD_RES_LAYOUT = "layout"; //$NON-NLS-1$
    /** Default menu resource folder name, i.e. "menu" */
    public static final String FD_RES_MENU = "menu"; //$NON-NLS-1$
    /** Default menu resource folder name, i.e. "mipmap" */
    public static final String FD_RES_MIPMAP = "mipmap"; //$NON-NLS-1$
    /** Default values resource folder name, i.e. "values" */
    public static final String FD_RES_VALUES = "values"; //$NON-NLS-1$
    /** Default xml resource folder name, i.e. "xml" */
    public static final String FD_RES_XML = "xml"; //$NON-NLS-1$
    /** Default raw resource folder name, i.e. "raw" */
    public static final String FD_RES_RAW = "raw"; //$NON-NLS-1$
    /** Separator between the resource folder qualifier. */
    public static final String RES_QUALIFIER_SEP = "-"; //$NON-NLS-1$
    /** Namespace used in XML files for Android attributes */

    // ---- XML ----

    /** URI of the reserved "xmlns"  prefix */
    public static final String XMLNS_URI = "http://www.w3.org/2000/xmlns/";  //$NON-NLS-1$
    /** The "xmlns" attribute name */
    public static final String XMLNS = "xmlns";                              //$NON-NLS-1$
    /** The default prefix used for the {@link #XMLNS_URI} */
    public static final String XMLNS_PREFIX = "xmlns:";                      //$NON-NLS-1$
    /** Qualified name of the xmlns android declaration element */
    public static final String XMLNS_ANDROID = "xmlns:android";              //$NON-NLS-1$
    /** The default prefix used for the {@link #ANDROID_URI} name space */
    public static final String ANDROID_NS_NAME = "android";                  //$NON-NLS-1$
    /** The default prefix used for the {@link #ANDROID_URI} name space including the colon  */
    public static final String ANDROID_NS_NAME_PREFIX = "android:";          //$NON-NLS-1$
    /** The default prefix used for the app */
    public static final String APP_PREFIX = "app";                          //$NON-NLS-1$
    /** The entity for the ampersand character */
    public static final String AMP_ENTITY = "&amp;";                         //$NON-NLS-1$
    /** The entity for the quote character */
    public static final String QUOT_ENTITY = "&quot;";                       //$NON-NLS-1$
    /** The entity for the apostrophe character */
    public static final String APOS_ENTITY = "&apos;";                       //$NON-NLS-1$
    /** The entity for the less than character */
    public static final String LT_ENTITY = "&lt;";                           //$NON-NLS-1$
    /** The entity for the greater than character */
    public static final String GT_ENTITY = "&gt;";                           //$NON-NLS-1$

    // ---- Elements and Attributes ----

    /** Namespace prefix used for all resources */
    public static final String URI_PREFIX =
            "http://schemas.android.com/apk/res/";                     //$NON-NLS-1$
    /** Namespace used in XML files for Android attributes */
    public static final String ANDROID_URI =
            "http://schemas.android.com/apk/res/android";              //$NON-NLS-1$
    /** Namespace used in XML files for Android Tooling attributes */
    public static final String TOOLS_URI =
            "http://schemas.android.com/tools";                        //$NON-NLS-1$
    /** Namespace used for auto-adjusting namespaces */
    public static final String AUTO_URI =
            "http://schemas.android.com/apk/res-auto";                 //$NON-NLS-1$
    /** Default prefix used for tools attributes */
    public static final String TOOLS_PREFIX = "tools";                 //$NON-NLS-1$
    public static final String R_CLASS = "R";                          //$NON-NLS-1$
    public static final String ANDROID_PKG = "android";                //$NON-NLS-1$

    // Tags: Manifest
    public static final String TAG_SERVICE = "service";                //$NON-NLS-1$
    public static final String TAG_USES_PERMISSION = "uses-permission";//$NON-NLS-1$
    public static final String TAG_USES_LIBRARY = "uses-library";      //$NON-NLS-1$
    public static final String TAG_APPLICATION = "application";        //$NON-NLS-1$
    public static final String TAG_INTENT_FILTER = "intent-filter";    //$NON-NLS-1$
    public static final String TAG_USES_SDK = "uses-sdk";              //$NON-NLS-1$
    public static final String TAG_ACTIVITY = "activity";              //$NON-NLS-1$
    public static final String TAG_RECEIVER = "receiver";              //$NON-NLS-1$
    public static final String TAG_PROVIDER = "provider";              //$NON-NLS-1$
    public static final String TAG_GRANT_PERMISSION = "grant-uri-permission"; //$NON-NLS-1$
    public static final String TAG_PATH_PERMISSION = "path-permission"; //$NON-NLS-1$

    // Tags: Resources
    public static final String TAG_RESOURCES = "resources";            //$NON-NLS-1$
    public static final String TAG_STRING = "string";                  //$NON-NLS-1$
    public static final String TAG_ARRAY = "array";                    //$NON-NLS-1$
    public static final String TAG_STYLE = "style";                    //$NON-NLS-1$
    public static final String TAG_ITEM = "item";                      //$NON-NLS-1$
    public static final String TAG_STRING_ARRAY = "string-array";      //$NON-NLS-1$
    public static final String TAG_PLURALS = "plurals";                //$NON-NLS-1$
    public static final String TAG_INTEGER_ARRAY = "integer-array";    //$NON-NLS-1$
    public static final String TAG_COLOR = "color";                    //$NON-NLS-1$
    public static final String TAG_DIMEN = "dimen";                    //$NON-NLS-1$
    public static final String TAG_DRAWABLE = "drawable";              //$NON-NLS-1$
    public static final String TAG_MENU = "menu";                      //$NON-NLS-1$

    // Tags: Layouts
    public static final String VIEW_TAG = "view";                      //$NON-NLS-1$
    public static final String VIEW_INCLUDE = "include";               //$NON-NLS-1$
    public static final String VIEW_MERGE = "merge";                   //$NON-NLS-1$
    public static final String VIEW_FRAGMENT = "fragment";             //$NON-NLS-1$
    public static final String REQUEST_FOCUS = "requestFocus";         //$NON-NLS-1$

    public static final String VIEW = "View";                          //$NON-NLS-1$
    public static final String VIEW_GROUP = "ViewGroup";               //$NON-NLS-1$
    public static final String FRAME_LAYOUT = "FrameLayout";           //$NON-NLS-1$
    public static final String LINEAR_LAYOUT = "LinearLayout";         //$NON-NLS-1$
    public static final String RELATIVE_LAYOUT = "RelativeLayout";     //$NON-NLS-1$
    public static final String GRID_LAYOUT = "GridLayout";             //$NON-NLS-1$
    public static final String SCROLL_VIEW = "ScrollView";             //$NON-NLS-1$
    public static final String BUTTON = "Button";                      //$NON-NLS-1$
    public static final String COMPOUND_BUTTON = "CompoundButton";     //$NON-NLS-1$
    public static final String ADAPTER_VIEW = "AdapterView";           //$NON-NLS-1$
    public static final String GALLERY = "Gallery";                    //$NON-NLS-1$
    public static final String GRID_VIEW = "GridView";                 //$NON-NLS-1$
    public static final String TAB_HOST = "TabHost";                   //$NON-NLS-1$
    public static final String RADIO_GROUP = "RadioGroup";             //$NON-NLS-1$
    public static final String RADIO_BUTTON = "RadioButton";           //$NON-NLS-1$
    public static final String SWITCH = "Switch";                      //$NON-NLS-1$
    public static final String EDIT_TEXT = "EditText";                 //$NON-NLS-1$
    public static final String LIST_VIEW = "ListView";                 //$NON-NLS-1$
    public static final String TEXT_VIEW = "TextView";                 //$NON-NLS-1$
    public static final String CHECKED_TEXT_VIEW = "CheckedTextView";  //$NON-NLS-1$
    public static final String IMAGE_VIEW = "ImageView";               //$NON-NLS-1$
    public static final String SURFACE_VIEW = "SurfaceView";           //$NON-NLS-1$
    public static final String ABSOLUTE_LAYOUT = "AbsoluteLayout";     //$NON-NLS-1$
    public static final String TABLE_LAYOUT = "TableLayout";           //$NON-NLS-1$
    public static final String TABLE_ROW = "TableRow";                 //$NON-NLS-1$
    public static final String TAB_WIDGET = "TabWidget";               //$NON-NLS-1$
    public static final String IMAGE_BUTTON = "ImageButton";           //$NON-NLS-1$
    public static final String SEEK_BAR = "SeekBar";                   //$NON-NLS-1$
    public static final String VIEW_STUB = "ViewStub";                 //$NON-NLS-1$
    public static final String SPINNER = "Spinner";                    //$NON-NLS-1$
    public static final String WEB_VIEW = "WebView";                   //$NON-NLS-1$
    public static final String TOGGLE_BUTTON = "ToggleButton";         //$NON-NLS-1$
    public static final String CHECK_BOX = "CheckBox";                 //$NON-NLS-1$
    public static final String ABS_LIST_VIEW = "AbsListView";          //$NON-NLS-1$
    public static final String PROGRESS_BAR = "ProgressBar";           //$NON-NLS-1$
    public static final String ABS_SPINNER = "AbsSpinner";             //$NON-NLS-1$
    public static final String ABS_SEEK_BAR = "AbsSeekBar";            //$NON-NLS-1$
    public static final String VIEW_ANIMATOR = "ViewAnimator";         //$NON-NLS-1$
    public static final String VIEW_SWITCHER = "ViewSwitcher";         //$NON-NLS-1$
    public static final String EXPANDABLE_LIST_VIEW = "ExpandableListView";    //$NON-NLS-1$
    public static final String HORIZONTAL_SCROLL_VIEW = "HorizontalScrollView"; //$NON-NLS-1$
    public static final String MULTI_AUTO_COMPLETE_TEXT_VIEW = "MultiAutoCompleteTextView"; //$NON-NLS-1$
    public static final String AUTO_COMPLETE_TEXT_VIEW = "AutoCompleteTextView"; //$NON-NLS-1$

    // Tags: Drawables
    public static final String TAG_BITMAP = "bitmap";                  //$NON-NLS-1$

    // Attributes: Manifest
    public static final String ATTR_EXPORTED = "exported";             //$NON-NLS-1$
    public static final String ATTR_PERMISSION = "permission";         //$NON-NLS-1$
    public static final String ATTR_MIN_SDK_VERSION = "minSdkVersion"; //$NON-NLS-1$
    public static final String ATTR_TARGET_SDK_VERSION = "targetSdkVersion"; //$NON-NLS-1$
    public static final String ATTR_ICON = "icon";                     //$NON-NLS-1$
    public static final String ATTR_PACKAGE = "package";               //$NON-NLS-1$
    public static final String ATTR_THEME = "theme";                   //$NON-NLS-1$
    public static final String ATTR_PATH = "path";                     //$NON-NLS-1$
    public static final String ATTR_PATH_PREFIX = "pathPrefix";        //$NON-NLS-1$
    public static final String ATTR_PATH_PATTERN = "pathPattern";      //$NON-NLS-1$
    public static final String ATTR_ALLOW_BACKUP = "allowBackup";      //$NON_NLS-1$
    public static final String ATTR_DEBUGGABLE = "debuggable";         //$NON-NLS-1$
    public static final String ATTR_READ_PERMISSION = "readPermission"; //$NON_NLS-1$
    public static final String ATTR_WRITE_PERMISSION = "writePermission"; //$NON_NLS-1$

    // Attributes: Resources
    public static final String ATTR_NAME = "name";                     //$NON-NLS-1$
    public static final String ATTR_TYPE = "type";                     //$NON-NLS-1$
    public static final String ATTR_PARENT = "parent";                 //$NON-NLS-1$
    public static final String ATTR_TRANSLATABLE = "translatable";     //$NON-NLS-1$
    public static final String ATTR_COLOR = "color";                   //$NON-NLS-1$

    // Attributes: Layout
    public static final String ATTR_LAYOUT_RESOURCE_PREFIX = "layout_";         //$NON-NLS-1$
    public static final String ATTR_CLASS = "class";                   //$NON-NLS-1$
    public static final String ATTR_STYLE = "style";                   //$NON-NLS-1$
    public static final String ATTR_CONTEXT = "context";               //$NON-NLS-1$
    public static final String ATTR_ID = "id";                         //$NON-NLS-1$
    public static final String ATTR_TEXT = "text";                     //$NON-NLS-1$
    public static final String ATTR_TEXT_SIZE = "textSize";            //$NON-NLS-1$
    public static final String ATTR_LABEL = "label";                   //$NON-NLS-1$
    public static final String ATTR_HINT = "hint";                     //$NON-NLS-1$
    public static final String ATTR_PROMPT = "prompt";                 //$NON-NLS-1$
    public static final String ATTR_ON_CLICK = "onClick";              //$NON-NLS-1$
    public static final String ATTR_INPUT_TYPE = "inputType";          //$NON-NLS-1$
    public static final String ATTR_INPUT_METHOD = "inputMethod";      //$NON-NLS-1$
    public static final String ATTR_LAYOUT_GRAVITY = "layout_gravity"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_WIDTH = "layout_width";     //$NON-NLS-1$
    public static final String ATTR_LAYOUT_HEIGHT = "layout_height";   //$NON-NLS-1$
    public static final String ATTR_LAYOUT_WEIGHT = "layout_weight";   //$NON-NLS-1$
    public static final String ATTR_PADDING = "padding";               //$NON-NLS-1$
    public static final String ATTR_PADDING_BOTTOM = "paddingBottom";  //$NON-NLS-1$
    public static final String ATTR_PADDING_TOP = "paddingTop";        //$NON-NLS-1$
    public static final String ATTR_PADDING_RIGHT = "paddingRight";    //$NON-NLS-1$
    public static final String ATTR_PADDING_LEFT = "paddingLeft";      //$NON-NLS-1$
    public static final String ATTR_FOREGROUND = "foreground";         //$NON-NLS-1$
    public static final String ATTR_BACKGROUND = "background";         //$NON-NLS-1$
    public static final String ATTR_ORIENTATION = "orientation";       //$NON-NLS-1$
    public static final String ATTR_LAYOUT = "layout";                 //$NON-NLS-1$
    public static final String ATTR_ROW_COUNT = "rowCount";            //$NON-NLS-1$
    public static final String ATTR_COLUMN_COUNT = "columnCount";      //$NON-NLS-1$
    public static final String ATTR_LABEL_FOR = "labelFor";            //$NON-NLS-1$
    public static final String ATTR_BASELINE_ALIGNED = "baselineAligned";       //$NON-NLS-1$
    public static final String ATTR_CONTENT_DESCRIPTION = "contentDescription"; //$NON-NLS-1$
    public static final String ATTR_IME_ACTION_LABEL = "imeActionLabel";        //$NON-NLS-1$
    public static final String ATTR_PRIVATE_IME_OPTIONS = "privateImeOptions";  //$NON-NLS-1$
    public static final String VALUE_NONE = "none";                    //$NON-NLS-1$
    public static final String VALUE_NO = "no";                        //$NON-NLS-1$
    public static final String ATTR_NUMERIC = "numeric";               //$NON-NLS-1$
    public static final String ATTR_IME_ACTION_ID = "imeActionId";     //$NON-NLS-1$
    public static final String ATTR_IME_OPTIONS = "imeOptions";        //$NON-NLS-1$
    public static final String ATTR_FREEZES_TEXT = "freezesText";      //$NON-NLS-1$
    public static final String ATTR_EDITOR_EXTRAS = "editorExtras";    //$NON-NLS-1$
    public static final String ATTR_EDITABLE = "editable";             //$NON-NLS-1$
    public static final String ATTR_DIGITS = "digits";                 //$NON-NLS-1$
    public static final String ATTR_CURSOR_VISIBLE = "cursorVisible";  //$NON-NLS-1$
    public static final String ATTR_CAPITALIZE = "capitalize";         //$NON-NLS-1$
    public static final String ATTR_PHONE_NUMBER = "phoneNumber";      //$NON-NLS-1$
    public static final String ATTR_PASSWORD = "password";             //$NON-NLS-1$
    public static final String ATTR_BUFFER_TYPE = "bufferType";        //$NON-NLS-1$
    public static final String ATTR_AUTO_TEXT = "autoText";            //$NON-NLS-1$
    public static final String ATTR_ENABLED = "enabled";               //$NON-NLS-1$
    public static final String ATTR_SINGLE_LINE = "singleLine";        //$NON-NLS-1$
    public static final String ATTR_SCALE_TYPE = "scaleType";          //$NON-NLS-1$
    public static final String ATTR_IMPORTANT_FOR_ACCESSIBILITY =
            "importantForAccessibility";                               //$NON-NLS-1$

    // AbsoluteLayout layout params
    public static final String ATTR_LAYOUT_Y = "layout_y";             //$NON-NLS-1$
    public static final String ATTR_LAYOUT_X = "layout_x";             //$NON-NLS-1$

    // GridLayout layout params
    public static final String ATTR_LAYOUT_ROW = "layout_row";         //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ROW_SPAN = "layout_rowSpan";//$NON-NLS-1$
    public static final String ATTR_LAYOUT_COLUMN = "layout_column";   //$NON-NLS-1$
    public static final String ATTR_LAYOUT_COLUMN_SPAN = "layout_columnSpan";       //$NON-NLS-1$

    // TableRow
    public static final String ATTR_LAYOUT_SPAN = "layout_span";       //$NON-NLS-1$

    // RelativeLayout layout params:
    public static final String ATTR_LAYOUT_ALIGN_LEFT = "layout_alignLeft";        //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_RIGHT = "layout_alignRight";      //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_TOP = "layout_alignTop";          //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_BOTTOM = "layout_alignBottom";    //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_PARENT_TOP = "layout_alignParentTop"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_PARENT_BOTTOM = "layout_alignParentBottom"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_PARENT_LEFT = "layout_alignParentLeft";//$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_PARENT_RIGHT = "layout_alignParentRight";   //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_WITH_PARENT_MISSING = "layout_alignWithParentIfMissing"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ALIGN_BASELINE = "layout_alignBaseline"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_CENTER_IN_PARENT = "layout_centerInParent"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_CENTER_VERTICAL = "layout_centerVertical"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_CENTER_HORIZONTAL = "layout_centerHorizontal"; //$NON-NLS-1$
    public static final String ATTR_LAYOUT_TO_RIGHT_OF = "layout_toRightOf";    //$NON-NLS-1$
    public static final String ATTR_LAYOUT_TO_LEFT_OF = "layout_toLeftOf";      //$NON-NLS-1$
    public static final String ATTR_LAYOUT_BELOW = "layout_below";              //$NON-NLS-1$
    public static final String ATTR_LAYOUT_ABOVE = "layout_above";              //$NON-NLS-1$

    // Margins
    public static final String ATTR_LAYOUT_MARGIN = "layout_margin";               //$NON-NLS-1$
    public static final String ATTR_LAYOUT_MARGIN_LEFT = "layout_marginLeft";      //$NON-NLS-1$
    public static final String ATTR_LAYOUT_MARGIN_RIGHT = "layout_marginRight";    //$NON-NLS-1$
    public static final String ATTR_LAYOUT_MARGIN_TOP = "layout_marginTop";        //$NON-NLS-1$
    public static final String ATTR_LAYOUT_MARGIN_BOTTOM = "layout_marginBottom";  //$NON-NLS-1$

    // Attributes: Drawables
    public static final String ATTR_TILE_MODE = "tileMode";            //$NON-NLS-1$

    // Values: Layouts
    public static final String VALUE_FILL_PARENT = "fill_parent";       //$NON-NLS-1$
    public static final String VALUE_MATCH_PARENT = "match_parent";     //$NON-NLS-1$
    public static final String VALUE_VERTICAL = "vertical";             //$NON-NLS-1$
    public static final String VALUE_TRUE = "true";                     //$NON-NLS-1$
    public static final String VALUE_EDITABLE = "editable";             //$NON-NLS-1$


    // Values: Resources
    public static final String VALUE_ID = "id";                        //$NON-NLS-1$

    // Values: Drawables
    public static final String VALUE_DISABLED = "disabled";            //$NON-NLS-1$
    public static final String VALUE_CLAMP = "clamp";                  //$NON-NLS-1$

    // Menus
    public static final String ATTR_SHOW_AS_ACTION = "showAsAction";   //$NON-NLS-1$
    public static final String VALUE_IF_ROOM = "ifRoom";               //$NON-NLS-1$
    public static final String VALUE_ALWAYS = "always";                //$NON-NLS-1$

    // Units
    public static final String UNIT_DP = "dp";                         //$NON-NLS-1$
    public static final String UNIT_DIP = "dip";                       //$NON-NLS-1$
    public static final String UNIT_SP = "sp";                         //$NON-NLS-1$
    public static final String UNIT_PX = "px";                         //$NON-NLS-1$
    public static final String UNIT_IN = "in";                         //$NON-NLS-1$
    public static final String UNIT_MM = "mm";                         //$NON-NLS-1$
    public static final String UNIT_PT = "pt";                         //$NON-NLS-1$

    // Filenames and folder names
    public static final String ANDROID_MANIFEST_XML = "AndroidManifest.xml"; //$NON-NLS-1$
    public static final String OLD_PROGUARD_FILE = "proguard.cfg";     //$NON-NLS-1$
    public static final String CLASS_FOLDER =
            "bin" + File.separator + "classes";                        //$NON-NLS-1$ //$NON-NLS-2$
    public static final String GEN_FOLDER = "gen";                     //$NON-NLS-1$
    public static final String SRC_FOLDER = "src";                     //$NON-NLS-1$
    public static final String LIBS_FOLDER = "libs";                   //$NON-NLS-1$
    public static final String BIN_FOLDER = "bin";                     //$NON-NLS-1$

    public static final String RES_FOLDER = "res";                     //$NON-NLS-1$
    public static final String DOT_XML = ".xml";                       //$NON-NLS-1$
    public static final String DOT_GIF = ".gif";                       //$NON-NLS-1$
    public static final String DOT_JPG = ".jpg";                       //$NON-NLS-1$
    public static final String DOT_PNG = ".png";                       //$NON-NLS-1$
    public static final String DOT_9PNG = ".9.png";                    //$NON-NLS-1$
    public static final String DOT_JAVA = ".java";                     //$NON-NLS-1$
    public static final String DOT_CLASS = ".class";                   //$NON-NLS-1$
    public static final String DOT_JAR = ".jar";                       //$NON-NLS-1$


    /** Extension of the Application package Files, i.e. "apk". */
    public static final String EXT_ANDROID_PACKAGE = "apk"; //$NON-NLS-1$
    /** Extension of java files, i.e. "java" */
    public static final String EXT_JAVA = "java"; //$NON-NLS-1$
    /** Extension of compiled java files, i.e. "class" */
    public static final String EXT_CLASS = "class"; //$NON-NLS-1$
    /** Extension of xml files, i.e. "xml" */
    public static final String EXT_XML = "xml"; //$NON-NLS-1$
    /** Extension of jar files, i.e. "jar" */
    public static final String EXT_JAR = "jar"; //$NON-NLS-1$
    /** Extension of aidl files, i.e. "aidl" */
    public static final String EXT_AIDL = "aidl"; //$NON-NLS-1$
    /** Extension of Renderscript files, i.e. "rs" */
    public static final String EXT_RS = "rs"; //$NON-NLS-1$
    /** Extension of dependency files, i.e. "d" */
    public static final String EXT_DEP = "d"; //$NON-NLS-1$
    /** Extension of native libraries, i.e. "so" */
    public static final String EXT_NATIVE_LIB = "so"; //$NON-NLS-1$
    /** Extension of dex files, i.e. "dex" */
    public static final String EXT_DEX = "dex"; //$NON-NLS-1$
    /** Extension for temporary resource files, ie "ap_ */
    public static final String EXT_RES = "ap_"; //$NON-NLS-1$
    /** Extension for pre-processable images. Right now pngs */
    public static final String EXT_PNG = "png"; //$NON-NLS-1$

    private final static String DOT = "."; //$NON-NLS-1$

    /** Dot-Extension of the Application package Files, i.e. ".apk". */
    public static final String DOT_ANDROID_PACKAGE = DOT + EXT_ANDROID_PACKAGE;
    /** Dot-Extension of aidl files, i.e. ".aidl" */
    public static final String DOT_AIDL = DOT + EXT_AIDL;
    /** Dot-Extension of renderscript files, i.e. ".rs" */
    public static final String DOT_RS = DOT + EXT_RS;
    /** Dot-Extension of dependency files, i.e. ".d" */
    public static final String DOT_DEP = DOT + EXT_DEP;
    /** Dot-Extension of dex files, i.e. ".dex" */
    public static final String DOT_DEX = DOT + EXT_DEX;
    /** Dot-Extension for temporary resource files, ie "ap_ */
    public static final String DOT_RES = DOT + EXT_RES;
    /** Dot-Extension for BMP files, i.e. ".bmp" */
    public static final String DOT_BMP = ".bmp"; //$NON-NLS-1$
    /** Dot-Extension for SVG files, i.e. ".svg" */
    public static final String DOT_SVG = ".svg"; //$NON-NLS-1$
    /** Dot-Extension for template files */
    public static final String DOT_FTL = ".ftl"; //$NON-NLS-1$
    /** Dot-Extension of text files, i.e. ".txt" */
    public static final String DOT_TXT = ".txt"; //$NON-NLS-1$

    /** Resource base name for java files and classes */
    public static final String FN_RESOURCE_BASE = "R"; //$NON-NLS-1$
    /** Resource java class  filename, i.e. "R.java" */
    public static final String FN_RESOURCE_CLASS = FN_RESOURCE_BASE + DOT_JAVA;
    /** Resource class file  filename, i.e. "R.class" */
    public static final String FN_COMPILED_RESOURCE_CLASS = FN_RESOURCE_BASE + DOT_CLASS;
    /** Manifest java class filename, i.e. "Manifest.java" */
    public static final String FN_MANIFEST_CLASS = "Manifest.java"; //$NON-NLS-1$

    public static final String DRAWABLE_FOLDER = "drawable";           //$NON-NLS-1$
    public static final String DRAWABLE_XHDPI = "drawable-xhdpi";      //$NON-NLS-1$
    public static final String DRAWABLE_HDPI = "drawable-hdpi";        //$NON-NLS-1$
    public static final String DRAWABLE_MDPI = "drawable-mdpi";        //$NON-NLS-1$
    public static final String DRAWABLE_LDPI = "drawable-ldpi";        //$NON-NLS-1$

    // Resources
    public static final String PREFIX_RESOURCE_REF = "@";               //$NON-NLS-1$
    public static final String PREFIX_THEME_REF = "?";                  //$NON-NLS-1$
    public static final String ANDROID_PREFIX = "@android:";            //$NON-NLS-1$
    public static final String ANDROID_THEME_PREFIX = "?android:";      //$NON-NLS-1$
    public static final String LAYOUT_RESOURCE_PREFIX = "@layout/";     //$NON-NLS-1$
    public static final String STYLE_RESOURCE_PREFIX = "@style/";       //$NON-NLS-1$
    public static final String NEW_ID_PREFIX = "@+id/";                 //$NON-NLS-1$
    public static final String ID_PREFIX = "@id/";                      //$NON-NLS-1$
    public static final String DRAWABLE_PREFIX = "@drawable/";          //$NON-NLS-1$
    public static final String STRING_PREFIX = "@string/";              //$NON-NLS-1$
    public static final String ANDROID_STRING_PREFIX = "@android:string/"; //$NON-NLS-1$
    public static final String ANDROID_LAYOUT_RESOURCE_PREFIX = "@android:layout/"; //$NON-NLS-1$

    public static final String RESOURCE_CLZ_ID = "id";                  //$NON-NLS-1$
    public static final String RESOURCE_CLZ_COLOR = "color";            //$NON-NLS-1$
    public static final String RESOURCE_CLZ_ARRAY = "array";            //$NON-NLS-1$
    public static final String RESOURCE_CLZ_ATTR = "attr";              //$NON-NLS-1$
    public static final String RESOURCE_CLR_STYLEABLE = "styleable";    //$NON-NLS-1$
    public static final String NULL_RESOURCE = "@null";                 //$NON-NLS-1$
    public static final String TRANSPARENT_COLOR = "@android:color/transparent";      //$NON-NLS-1$
    public static final String ANDROID_STYLE_RESOURCE_PREFIX = "@android:style/";     //$NON-NLS-1$
    public static final String REFERENCE_STYLE = "style/";                     //$NON-NLS-1$
    public static final String PREFIX_ANDROID = "android:";                    //$NON-NLS-1$

    // Packages
    public static final String ANDROID_PKG_PREFIX = "android.";         //$NON-NLS-1$
    public static final String WIDGET_PKG_PREFIX = "android.widget.";   //$NON-NLS-1$
    public static final String VIEW_PKG_PREFIX = "android.view.";       //$NON-NLS-1$

    // Project properties
    public static final String ANDROID_LIBRARY = "android.library";     //$NON-NLS-1$
    public static final String PROGUARD_CONFIG = "proguard.config";     //$NON-NLS-1$
    public static final String ANDROID_LIBRARY_REFERENCE_FORMAT = "android.library.reference.%1$d";//$NON-NLS-1$
    public static final String PROJECT_PROPERTIES = "project.properties";//$NON-NLS-1$

    // Java References
    public static final String ATTR_REF_PREFIX = "?attr/";               //$NON-NLS-1$
    public static final String R_PREFIX = "R.";                          //$NON-NLS-1$
    public static final String R_ID_PREFIX = "R.id.";                    //$NON-NLS-1$
    public static final String R_LAYOUT_RESOURCE_PREFIX = "R.layout.";            //$NON-NLS-1$
    public static final String R_DRAWABLE_PREFIX = "R.drawable.";        //$NON-NLS-1$
    public static final String R_ATTR_PREFIX = "R.attr.";                //$NON-NLS-1$

    // Attributes related to tools
    public static final String ATTR_IGNORE = "ignore";                   //$NON-NLS-1$

    // SuppressLint
    public static final String SUPPRESS_ALL = "all";                     //$NON-NLS-1$
    public static final String SUPPRESS_LINT = "SuppressLint";           //$NON-NLS-1$
    public static final String TARGET_API = "TargetApi";                 //$NON-NLS-1$
    public static final String FQCN_SUPPRESS_LINT = "android.annotation." + SUPPRESS_LINT; //$NON-NLS-1$
    public static final String FQCN_TARGET_API = "android.annotation." + TARGET_API; //$NON-NLS-1$

    // Class Names
    public static final String CONSTRUCTOR_NAME = "<init>";                          //$NON-NLS-1$
    public static final String CLASS_CONSTRUCTOR = "<clinit>";                       //$NON-NLS-1$
    public static final String FRAGMENT = "android/app/Fragment";                    //$NON-NLS-1$
    public static final String FRAGMENT_V4 = "android/support/v4/app/Fragment";      //$NON-NLS-1$
    public static final String ANDROID_APP_ACTIVITY = "android/app/Activity";        //$NON-NLS-1$
    public static final String ANDROID_APP_SERVICE = "android/app/Service";          //$NON-NLS-1$
    public static final String ANDROID_CONTENT_CONTENT_PROVIDER =
            "android/content/ContentProvider";                                       //$NON-NLS-1$
    public static final String ANDROID_CONTENT_BROADCAST_RECEIVER =
            "android/content/BroadcastReceiver";                                     //$NON-NLS-1$

    // Method Names
    public static final String FORMAT_METHOD = "format";                             //$NON-NLS-1$
    public static final String GET_STRING_METHOD = "getString";                      //$NON-NLS-1$




    public static final String ATTR_TAG = "tag";                        //$NON-NLS-1$
    public static final String ATTR_NUM_COLUMNS = "numColumns";         //$NON-NLS-1$

    // Some common layout element names
    public static final String CALENDAR_VIEW = "CalendarView";          //$NON-NLS-1$
    public static final String SPACE = "Space";                         //$NON-NLS-1$
    public static final String GESTURE_OVERLAY_VIEW = "GestureOverlayView";//$NON-NLS-1$

    public static final String ATTR_HANDLE = "handle";                  //$NON-NLS-1$
    public static final String ATTR_CONTENT = "content";                //$NON-NLS-1$
    public static final String ATTR_CHECKED = "checked";                //$NON-NLS-1$

    // TextView
    public static final String ATTR_DRAWABLE_RIGHT = "drawableRight";              //$NON-NLS-1$
    public static final String ATTR_DRAWABLE_LEFT = "drawableLeft";                //$NON-NLS-1$
    public static final String ATTR_DRAWABLE_BOTTOM = "drawableBottom";            //$NON-NLS-1$
    public static final String ATTR_DRAWABLE_TOP = "drawableTop";                  //$NON-NLS-1$
    public static final String ATTR_DRAWABLE_PADDING = "drawablePadding";          //$NON-NLS-1$

    public static final String ATTR_USE_DEFAULT_MARGINS = "useDefaultMargins";      //$NON-NLS-1$
    public static final String ATTR_MARGINS_INCLUDED_IN_ALIGNMENT = "marginsIncludedInAlignment"; //$NON-NLS-1$

    public static final String VALUE_WRAP_CONTENT = "wrap_content";             //$NON-NLS-1$
    public static final String VALUE_FALSE= "false";                            //$NON-NLS-1$
    public static final String VALUE_N_DP = "%ddp";                             //$NON-NLS-1$
    public static final String VALUE_ZERO_DP = "0dp";                           //$NON-NLS-1$
    public static final String VALUE_ONE_DP = "1dp";                            //$NON-NLS-1$
    public static final String VALUE_TOP = "top";                               //$NON-NLS-1$
    public static final String VALUE_LEFT = "left";                             //$NON-NLS-1$
    public static final String VALUE_RIGHT = "right";                           //$NON-NLS-1$
    public static final String VALUE_BOTTOM = "bottom";                         //$NON-NLS-1$
    public static final String VALUE_CENTER_VERTICAL = "center_vertical";       //$NON-NLS-1$
    public static final String VALUE_CENTER_HORIZONTAL = "center_horizontal";   //$NON-NLS-1$
    public static final String VALUE_FILL_HORIZONTAL = "fill_horizontal";       //$NON-NLS-1$
    public static final String VALUE_FILL_VERTICAL = "fill_vertical";           //$NON-NLS-1$
    public static final String VALUE_0 = "0";                                   //$NON-NLS-1$
    public static final String VALUE_1 = "1";                                   //$NON-NLS-1$

    // Gravity values. These have the GRAVITY_ prefix in front of value because we already
    // have VALUE_CENTER_HORIZONTAL defined for layouts, and its definition conflicts
    // (centerHorizontal versus center_horizontal)
    public static final String GRAVITY_VALUE_ = "center";                             //$NON-NLS-1$
    public static final String GRAVITY_VALUE_CENTER = "center";                       //$NON-NLS-1$
    public static final String GRAVITY_VALUE_RIGHT = "right";                         //$NON-NLS-1$
    public static final String GRAVITY_VALUE_LEFT = "left";                           //$NON-NLS-1$
    public static final String GRAVITY_VALUE_BOTTOM = "bottom";                       //$NON-NLS-1$
    public static final String GRAVITY_VALUE_TOP = "top";                             //$NON-NLS-1$
    public static final String GRAVITY_VALUE_FILL_HORIZONTAL = "fill_horizontal";     //$NON-NLS-1$
    public static final String GRAVITY_VALUE_FILL_VERTICAL = "fill_vertical";         //$NON-NLS-1$
    public static final String GRAVITY_VALUE_CENTER_HORIZONTAL = "center_horizontal"; //$NON-NLS-1$
    public static final String GRAVITY_VALUE_CENTER_VERTICAL = "center_vertical";     //$NON-NLS-1$
    public static final String GRAVITY_VALUE_FILL = "fill";                           //$NON-NLS-1$

    /**
     * The top level android package as a prefix, "android.".
     */
    public static final String ANDROID_SUPPORT_PKG_PREFIX = ANDROID_PKG_PREFIX + "support."; //$NON-NLS-1$

    /** The android.view. package prefix */
    public static final String ANDROID_VIEW_PKG = ANDROID_PKG_PREFIX + "view."; //$NON-NLS-1$

    /** The android.widget. package prefix */
    public static final String ANDROID_WIDGET_PREFIX = ANDROID_PKG_PREFIX + "widget."; //$NON-NLS-1$

    /** The android.webkit. package prefix */
    public static final String ANDROID_WEBKIT_PKG = ANDROID_PKG_PREFIX + "webkit."; //$NON-NLS-1$

    /** The LayoutParams inner-class name suffix, .LayoutParams */
    public static final String DOT_LAYOUT_PARAMS = ".LayoutParams"; //$NON-NLS-1$

    /** The fully qualified class name of an EditText view */
    public static final String FQCN_EDIT_TEXT = "android.widget.EditText"; //$NON-NLS-1$

    /** The fully qualified class name of a LinearLayout view */
    public static final String FQCN_LINEAR_LAYOUT = "android.widget.LinearLayout"; //$NON-NLS-1$

    /** The fully qualified class name of a RelativeLayout view */
    public static final String FQCN_RELATIVE_LAYOUT = "android.widget.RelativeLayout"; //$NON-NLS-1$

    /** The fully qualified class name of a RelativeLayout view */
    public static final String FQCN_GRID_LAYOUT = "android.widget.GridLayout"; //$NON-NLS-1$
    public static final String FQCN_GRID_LAYOUT_V7 = "android.support.v7.widget.GridLayout"; //$NON-NLS-1$

    /** The fully qualified class name of a FrameLayout view */
    public static final String FQCN_FRAME_LAYOUT = "android.widget.FrameLayout"; //$NON-NLS-1$

    /** The fully qualified class name of a TableRow view */
    public static final String FQCN_TABLE_ROW = "android.widget.TableRow"; //$NON-NLS-1$

    /** The fully qualified class name of a TableLayout view */
    public static final String FQCN_TABLE_LAYOUT = "android.widget.TableLayout"; //$NON-NLS-1$

    /** The fully qualified class name of a GridView view */
    public static final String FQCN_GRID_VIEW = "android.widget.GridView"; //$NON-NLS-1$

    /** The fully qualified class name of a TabWidget view */
    public static final String FQCN_TAB_WIDGET = "android.widget.TabWidget"; //$NON-NLS-1$

    /** The fully qualified class name of a Button view */
    public static final String FQCN_BUTTON = "android.widget.Button"; //$NON-NLS-1$

    /** The fully qualified class name of a RadioButton view */
    public static final String FQCN_RADIO_BUTTON = "android.widget.RadioButton"; //$NON-NLS-1$

    /** The fully qualified class name of a ToggleButton view */
    public static final String FQCN_TOGGLE_BUTTON = "android.widget.ToggleButton"; //$NON-NLS-1$

    /** The fully qualified class name of a Spinner view */
    public static final String FQCN_SPINNER = "android.widget.Spinner"; //$NON-NLS-1$

    /** The fully qualified class name of an AdapterView */
    public static final String FQCN_ADAPTER_VIEW = "android.widget.AdapterView"; //$NON-NLS-1$

    /** The fully qualified class name of a ListView */
    public static final String FQCN_LIST_VIEW = "android.widget.ListView"; //$NON-NLS-1$

    /** The fully qualified class name of an ExpandableListView */
    public static final String FQCN_EXPANDABLE_LIST_VIEW = "android.widget.ExpandableListView"; //$NON-NLS-1$

    /** The fully qualified class name of a GestureOverlayView */
    public static final String FQCN_GESTURE_OVERLAY_VIEW = "android.gesture.GestureOverlayView"; //$NON-NLS-1$

    /** The fully qualified class name of a DatePicker */
    public static final String FQCN_DATE_PICKER = "android.widget.DatePicker"; //$NON-NLS-1$

    /** The fully qualified class name of a TimePicker */
    public static final String FQCN_TIME_PICKER = "android.widget.TimePicker"; //$NON-NLS-1$

    /** The fully qualified class name of a RadioGroup */
    public static final String FQCN_RADIO_GROUP = "android.widgets.RadioGroup";  //$NON-NLS-1$

    /** The fully qualified class name of a Space */
    public static final String FQCN_SPACE = "android.widget.Space"; //$NON-NLS-1$
    public static final String FQCN_SPACE_V7 = "android.support.v7.widget.Space"; //$NON-NLS-1$

    /** The fully qualified class name of a TextView view */
    public static final String FQCN_TEXT_VIEW = "android.widget.TextView"; //$NON-NLS-1$

    /** The fully qualified class name of an ImageView view */
    public static final String FQCN_IMAGE_VIEW = "android.widget.ImageView"; //$NON-NLS-1$

    public static final String ATTR_SRC = "src"; //$NON-NLS-1$

    public static final String ATTR_GRAVITY = "gravity"; //$NON-NLS-1$
    public static final String ATTR_WEIGHT_SUM = "weightSum"; //$NON-NLS-1$
    public static final String ATTR_EMS = "ems"; //$NON-NLS-1$

    public static final String VALUE_HORIZONTAL = "horizontal"; //$NON-NLS-1$

    /**
     * The highest known API level. Note that the tools may also look at the
     * installed platforms to see if they can find more recently released
     * platforms, e.g. when the tools have not yet been updated for a new
     * release. This number is used as a baseline and any more recent platforms
     * found can be used to increase the highest known number.
     */
    public static final int HIGHEST_KNOWN_API = 16;
}
