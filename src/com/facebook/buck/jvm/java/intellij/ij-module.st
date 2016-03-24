<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
%if(androidFacet.enabled)%
  <component name="FacetManager">
    <facet type="android" name="Android">
      <configuration>
        <option name="GEN_FOLDER_RELATIVE_PATH_APT" value="%androidFacet.module_gen_path%" />
        <option name="GEN_FOLDER_RELATIVE_PATH_AIDL" value="%androidFacet.module_gen_path%" />
        <option name="MANIFEST_FILE_RELATIVE_PATH" value="%androidFacet.android_manifest%" />
        <option name="RES_FOLDERS_RELATIVE_PATH" value="%androidFacet.res%" />
        <option name="ASSETS_FOLDER_RELATIVE_PATH" value="%androidFacet.asset_folder%" />
        <option name="LIBS_FOLDER_RELATIVE_PATH" value="%androidFacet.libs_path%" />
        <option name="APK_PATH" value="%androidFacet.apk_path%" />
        <option name="LIBRARY_PROJECT" value="%androidFacet.is_android_library_project%" />
        <option name="RUN_PROCESS_RESOURCES_MAVEN_TASK" value="true" />
        <option name="GENERATE_UNSIGNED_APK" value="false" />
        <option name="CUSTOM_DEBUG_KEYSTORE_PATH" value="%androidFacet.keystore%" />
        <option name="PACK_TEST_CODE" value="false" />
        <option name="RUN_PROGUARD" value="%androidFacet.run_proguard%" />
        <option name="PROGUARD_CFG_PATH" value="%androidFacet.proguard_config%" />
        <resOverlayFolders />
        <includeSystemProguardFile>false</includeSystemProguardFile>
        <includeAssetsFromLibraries>true</includeAssetsFromLibraries>
        <additionalNativeLibs />
      </configuration>
    </facet>
  </component>
%endif%
  <component name="NewModuleRootManager" inherit-compiler-output="true">
    <content url="%contentRoot.url%">
%contentRoot.folders:{folder |
      <%folder.type% url="%folder.url%" %\\%
        isTestSource="%folder.isTestSource%" %\\%
        %if(folder.packagePrefix)% packagePrefix="%folder.packagePrefix%"%endif%%\\%
        %if(folder.isAndroidResources)% type="java-resource" %endif%/>
}%
    </content>
%if(androidFacet.enabled)%
    <content url="file://$MODULE_DIR$%androidFacet.module_gen_path%">
      <sourceFolder url="file://$MODULE_DIR$%androidFacet.module_gen_path%" isTestSource="false" />
    </content>
%endif%
%generatedSourceFolders:{generatedFolder |
    <content url="%generatedFolder.url%">
      <%generatedFolder.type% url="%generatedFolder.url%" %\\%
        isTestSource="%generatedFolder.isTestSource%" %\\%
        %if(generatedFolder.packagePrefix)% packagePrefix="%generatedFolder.packagePrefix%"%endif%%\\%
        %if(generatedFolder.isAndroidResources)% type="java-resource" %endif%/>
    </content>
}%
%dependencies:{dep |
  %if(dep.module)%
    <orderEntry type="%dep.type%" module-name="%dep.module.name%" scope="%dep.module.scope%" %\\%
      %if(dep.module.exported)% exported="" %endif% />
  %elseif(dep.library)%
    <orderEntry type="%dep.type%" name="%dep.library.name%" scope="%dep.library.scope%" %\\%
      level="project" %if(dep.library.exported)% exported="" %endif% />
  %elseif(dep.sourceFolder)%
    <orderEntry type="%dep.type%" forTests="false" />
  %endif%
}%
    <orderEntry type="inheritedJdk" />
  </component>
</module>
