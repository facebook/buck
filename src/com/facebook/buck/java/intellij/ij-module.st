<?xml version="1.0" encoding="UTF-8"?>
<module type="JAVA_MODULE" version="4">
%if(androidFacet)%
  <component name="FacetManager">
    <facet type="android" name="Android">
      <configuration>
        <option name="ENABLE_SOURCES_AUTOGENERATION" value="%enable_sources_autogeneration%" />
        <option name="GEN_FOLDER_RELATIVE_PATH_APT" value="%module_gen_path%" />
        <option name="GEN_FOLDER_RELATIVE_PATH_AIDL" value="%module_gen_path%" />
        <option name="MANIFEST_FILE_RELATIVE_PATH" value="%android_manifest%" />
        <option name="RES_FOLDER_RELATIVE_PATH" value="%res%" />
        <option name="ASSETS_FOLDER_RELATIVE_PATH" value="%asset_folder%" />
        <option name="LIBS_FOLDER_RELATIVE_PATH" value="%libs_path%" />
        <option name="USE_CUSTOM_APK_RESOURCE_FOLDER" value="false" />
        <option name="CUSTOM_APK_RESOURCE_FOLDER" value="" />
        <option name="USE_CUSTOM_COMPILER_MANIFEST" value="false" />
        <option name="CUSTOM_COMPILER_MANIFEST" value="" />
        <option name="APK_PATH" value="%apk_path%" />
        <option name="LIBRARY_PROJECT" value="%is_android_library_project%" />
        <option name="RUN_PROCESS_RESOURCES_MAVEN_TASK" value="true" />
        <option name="GENERATE_UNSIGNED_APK" value="false" />
        <option name="CUSTOM_DEBUG_KEYSTORE_PATH" value="%keystore%" />
        <option name="PACK_TEST_CODE" value="false" />
        <option name="RUN_PROGUARD" value="%run_proguard%" />
        <option name="PROGUARD_CFG_PATH" value="%proguard_config%" />
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
        %if(folder.packagePrefix)% packagePrefix="%folder.packagePrefix%" %endif% />
}%
    </content>
%dependencies:{dep |
  %if(dep.module)%
    <orderEntry type="%dep.type%" module-name="%dep.module.name%" scope="%dep.module.scope%" %\\%
      %if(dep.module.isExported)% exported="" %endif% />
  %elseif(dep.library)%
    <orderEntry type="%dep.type%" name="%dep.library.name%" scope="%dep.library.scope%" level="project" />
  %elseif(dep.sourceFolder)%
    <orderEntry type="%dep.type%" forTests="false" />
  %endif%
}%
    <orderEntry type="inheritedJdk" />
  </component>
</module>
