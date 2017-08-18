<?xml version="1.0" encoding="UTF-8"?>
<module type="%moduleType%" version="4">
%if(metaInfDirectory)%
  <component name="DevKit.ModuleBuildProperties" url="file://$MODULE_DIR$/%metaInfDirectory%/plugin.xml" />
%endif%
%if(androidFacet.enabled)%
  <component name="FacetManager">
    <facet type="android" name="Android">
      <configuration>
%if(androidFacet.disallow_user_configuration)%
        <option name="ALLOW_USER_CONFIGURATION" value="false" />
%endif%
        <option name="GEN_FOLDER_RELATIVE_PATH_APT" value="%androidFacet.module_gen_path%" />
        <option name="GEN_FOLDER_RELATIVE_PATH_AIDL" value="%androidFacet.module_gen_path%" />
%if(androidFacet.android_manifest)%
        <option name="MANIFEST_FILE_RELATIVE_PATH" value="%androidFacet.android_manifest%" />
%endif%
        <option name="RES_FOLDERS_RELATIVE_PATH" value="%androidFacet.res%" />
%if(androidFacet.asset_folder)%
        <option name="ASSETS_FOLDER_RELATIVE_PATH" value="%androidFacet.asset_folder%" />
%endif%
%if(androidFacet.apk_path)%
        <option name="APK_PATH" value="%androidFacet.apk_path%" />
%endif%
        <option name="LIBRARY_PROJECT" value="%androidFacet.is_android_library_project%" />
        <option name="PROJECT_TYPE" value="%androidFacet.project_type%" />
%if(androidFacet.keystore)%
        <option name="CUSTOM_DEBUG_KEYSTORE_PATH" value="%androidFacet.keystore%" />
%endif%
%if(androidFacet.proguard_config)%
        <option name="PROGUARD_CFG_PATH" value="%androidFacet.proguard_config%" />
%endif%
        <option name="UPDATE_PROPERTY_FILES" value="false" />
        <option name="ENABLE_SOURCES_AUTOGENERATION" value="%androidFacet.autogenerate_sources%" />
        <includeAssetsFromLibraries>true</includeAssetsFromLibraries>
      </configuration>
    </facet>
  </component>
%endif%
%if(languageLevel)%
  <component name="NewModuleRootManager" LANGUAGE_LEVEL="%languageLevel%" %\\%
    %if(androidFacet.compiler_output_path)%inherit-compiler-output="false"%else%inherit-compiler-output="true"%endif%>
%else%
  <component name="NewModuleRootManager" %\\%
    %if(androidFacet.compiler_output_path)%inherit-compiler-output="false"%else%inherit-compiler-output="true"%endif%>
%endif%
    %if(androidFacet.compiler_output_path)%
    <output url="%androidFacet.compiler_output_path%" />
    %endif%
    <exclude-output />
%if(androidFacet.enabled)%
    <content url="file://$MODULE_DIR$%androidFacet.module_gen_path%">
      <sourceFolder url="file://$MODULE_DIR$%androidFacet.module_gen_path%" isTestSource="false" />
    </content>
%endif%
%generatedSourceFolders:{generatedFolder |
    <content url="%generatedFolder.url%">
      <%generatedFolder.type% url="%generatedFolder.url%" %\\%
        isTestSource="%generatedFolder.isTestSource%" generated="true" %\\%
        %if(generatedFolder.packagePrefix)%packagePrefix="%generatedFolder.packagePrefix%" %endif%%\\%
        %if(generatedFolder.isAndroidResources)%type="java-resource" %endif%/>
    </content>
}%%contentRoots:{contentRoot |
    <content url="%contentRoot.url%">
%contentRoot.folders:{folder |
      <%folder.type% url="%folder.url%" %\\%
        isTestSource="%folder.isTestSource%" %\\%
        %if(folder.packagePrefix)%packagePrefix="%folder.packagePrefix%" %endif%%\\%
        %if(folder.isAndroidResources)%type="java-resource" %endif%/>
}%    </content>
}%%if(sdk)%    <orderEntry type="jdk" jdkName="%sdk%" jdkType="%sdkType%" />
%else%    <orderEntry type="inheritedJdk" />
%endif%
    <orderEntry type="sourceFolder" forTests="false" />
%dependencies:{dep |
  %if(dep.module)%
    <orderEntry type="%dep.type%" module-name="%dep.module.name%" scope="%dep.module.scope%" %\\%
      %if(dep.module.exported)%exported="" %endif%/>
  %elseif(dep.library)%
    <orderEntry type="%dep.type%" name="%dep.library.name%" scope="%dep.library.scope%" %\\%
      level="project" %if(dep.library.exported)%exported="" %endif%/>
  %endif%
}%  </component>
</module>