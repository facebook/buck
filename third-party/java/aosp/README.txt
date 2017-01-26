Android SDK (4.2.1_r1 branch) code for Manifest Merging

== Steps

1) Downloaded the Android SDK source code, follow http://source.android.com/source/downloading.html
(use branch android-4.2.1_r1)
2) Copied java class com.android.manifmerger.ManifestMerger and all its dependencies to the aosp/
module.
3) In the Android SDK's source code manifmerger, sdklib and utils are separate modules. These are
copied into a single module which alters certain package paths. For example,
com.android.SDKConstants becomes com.android.common.SDKConstants.
4) Added support for re-ordering <activity-alias> so they always go after <activity> elements.
5) Added support for merging <meta-data> elements of <application>.