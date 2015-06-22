import errno
import json
import os
import subprocess
import sys

from collections import defaultdict


MISC_XML = """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component
    name="ProjectRootManager"
    version="2"
    languageLevel="%(java_language_level)s"
    assert-keyword="true"
    jdk-15="true"
    project-jdk-name="%(project_jdk_name)s"
    project-jdk-type="%(project_jdk_type)s">
    %(project_output_url)s
  </component>
</project>"""

MODULE_XML_START = """<?xml version="1.0" encoding="UTF-8"?>
<module type="%(type)s" version="4">"""

MODULE_XML_END = """
</module>
"""

ANDROID_FACET = """
  <component name="FacetManager">
    <facet type="android" name="Android">
      <configuration>
        <option name="ENABLE_SOURCES_AUTOGENERATION" value="%(enable_sources_autogeneration)s" />
        <option name="GEN_FOLDER_RELATIVE_PATH_APT" value="%(module_gen_path)s" />
        <option name="GEN_FOLDER_RELATIVE_PATH_AIDL" value="%(module_gen_path)s" />
        <option name="MANIFEST_FILE_RELATIVE_PATH" value="%(android_manifest)s" />
        <option name="RES_FOLDER_RELATIVE_PATH" value="%(res)s" />
        <option name="ASSETS_FOLDER_RELATIVE_PATH" value="%(asset_folder)s" />
        <option name="LIBS_FOLDER_RELATIVE_PATH" value="%(libs_path)s" />
        <option name="USE_CUSTOM_APK_RESOURCE_FOLDER" value="false" />
        <option name="CUSTOM_APK_RESOURCE_FOLDER" value="" />
        <option name="USE_CUSTOM_COMPILER_MANIFEST" value="false" />
        <option name="CUSTOM_COMPILER_MANIFEST" value="" />
        <option name="APK_PATH" value="%(apk_path)s" />
        <option name="LIBRARY_PROJECT" value="%(is_android_library_project)s" />
        <option name="RUN_PROCESS_RESOURCES_MAVEN_TASK" value="true" />
        <option name="GENERATE_UNSIGNED_APK" value="false" />
        <option name="CUSTOM_DEBUG_KEYSTORE_PATH" value="%(keystore)s" />
        <option name="PACK_TEST_CODE" value="false" />
        <option name="RUN_PROGUARD" value="%(run_proguard)s" />
        <option name="PROGUARD_CFG_PATH" value="%(proguard_config)s" />
        <resOverlayFolders />
        <includeSystemProguardFile>false</includeSystemProguardFile>
        <includeAssetsFromLibraries>true</includeAssetsFromLibraries>
        <additionalNativeLibs />
      </configuration>
    </facet>
  </component>"""

ALL_MODULES_XML_START = """<?xml version="1.0" encoding="UTF-8"?>
<project version="4">
  <component name="ProjectModuleManager">
    <modules>"""

ALL_MODULES_XML_END = """
    </modules>
  </component>
</project>
"""

AAR_XML_START = """<component name="libraryTable">
  <library name="%(name)s">
    <CLASSES>
      <root url="jar://$PROJECT_DIR$/%(binary_jar)s!/" />"""

AAR_XML_RESOURCE = """
      <root url="file://$PROJECT_DIR$/%(resource_path)s/" />"""

AAR_XML_END = """
    </CLASSES>
  </library>
</component>
"""

LIBRARY_XML_START = """<component name="libraryTable">
  <library name="%(name)s">
    <CLASSES>
      <root url="jar://$PROJECT_DIR$/%(binary_jar)s!/" />
    </CLASSES>"""

LIBRARY_XML_WITH_JAVADOC = """
    <JAVADOC>
      <root url="%(javadoc_url)s" />
    </JAVADOC>"""

LIBRARY_XML_NO_JAVADOC = """
    <JAVADOC />"""

LIBRARY_XML_WITH_SOURCES = """
    <SOURCES>
      <root url="jar://$PROJECT_DIR$/%(source_jar)s!/" />
    </SOURCES>"""

LIBRARY_XML_NO_SOURCES = """
    <SOURCES />"""

LIBRARY_XML_END = """
  </library>
</component>
"""

RUN_CONFIG_XML_START = """<component name="ProjectRunConfigurationManager">"""
RUN_CONFIG_XML_END = "</component>"

REMOTE_RUN_CONFIG_XML = """
  <configuration default="false" name="%(name)s" type="Remote" factoryName="Remote">
    <option name="USE_SOCKET_TRANSPORT" value="true" />
    <option name="SERVER_MODE" value="false" />
    <option name="SHMEM_ADDRESS" value="javadebug" />
    <option name="HOST" value="localhost" />
    <option name="PORT" value="5005" />
    <RunnerSettings RunnerId="Debug">
      <option name="DEBUG_PORT" value="5005" />
      <option name="TRANSPORT" value="0" />
      <option name="LOCAL" value="false" />
    </RunnerSettings>
    <ConfigurationWrapper RunnerId="Debug" />
    <method />
  </configuration>
"""


# Files that were written by this script.
# If `buck project` is working properly, most of the time it will be a no-op
# and no files will need to be written.
MODIFIED_FILES = []

# Files that are part of the project being run.  We will delete all .iml files
# that are not checked in and not in this set.
PROJECT_FILES = set()

# Marker for a directory in the module tree that contains an .iml file.
# Intentionally chosen to be an illegal file name in both unix and windows.
CONTAINS_IML_MARKER = '/*contains_iml*/'


def tree():
    """ Create an autovivification tree """
    return defaultdict(tree)


def create_additional_excludes(modules):
    """Create set of directories to also be excluded."""

    # Tree representation of all modules.
    module_tree = tree()
    additional_excludes = defaultdict(list)
    for module in modules:
        normalized_iml = os.path.dirname(os.path.normpath(
            module['pathToImlFile']))

        # Add this path to our build tree
        current_directory = module_tree
        if normalized_iml:
            for part in normalized_iml.split(os.path.sep):
                current_directory = current_directory[part]

        current_directory[CONTAINS_IML_MARKER] = module

    for root, dirs, _files in os.walk('.', topdown=True, followlinks=True):
        current_directory = module_tree
        normalized_root = os.path.normpath(root)
        if normalized_root == '.':
            continue
        highest_iml_file = None
        for part in normalized_root.split(os.path.sep):
            if CONTAINS_IML_MARKER in current_directory:
                module = current_directory[CONTAINS_IML_MARKER]
                found_relevant_source_folder = False
                for source_folder in module['sourceFolders']:
                    # If we find a module that specifies the directory as the
                    # source folder, then keep all folders under that module.
                    #
                    # TODO(royw): Be smarter here and actually keep track of
                    # the additional directories being tracked by sub modules.
                    if source_folder['url'] != 'file://$MODULE_DIR$/gen':
                        found_relevant_source_folder = True
                        break
                # If we found a module containing subdirectories as
                # sourceFolders, bail on trying to find a higher IML file.
                if found_relevant_source_folder:
                    break
                highest_iml_file = module['pathToImlFile']
            if part not in current_directory:
                if part != 'res' and highest_iml_file:
                    additional_excludes[highest_iml_file].append(
                        normalized_root)
                dirs[:] = []
                break
            else:
                current_directory = current_directory[part]

    return additional_excludes


def get_path_from_map(map, key, fallback=None):
    if key in map:
        return '/' + map[key]

    if None != fallback:
        return '/' + fallback

    return ''


def write_modules(modules, generate_minimum_project, android_auto_generation_disabled):
    """Writes one XML file for each module."""
    additional_excludes = defaultdict(list)
    if generate_minimum_project:
        additional_excludes = create_additional_excludes(modules)

    for module in modules:
        # Build up the XML.
        module_type = 'JAVA_MODULE'
        if 'isIntelliJPlugin' in module and module['isIntelliJPlugin']:
            module_type = 'PLUGIN_MODULE'

        xml = MODULE_XML_START % {
            'type': module_type,
        }

        # Android facet, if appropriate.
        if module.get('hasAndroidFacet') is True:
            if 'keystorePath' in module:
                keystore = 'file://$MODULE_DIR$/%s' % module['keystorePath']
            else:
                keystore = ''

            android_manifest = get_path_from_map(module, 'androidManifest', 'AndroidManifest.xml')
            res_folder = get_path_from_map(module, 'resFolder', 'res')
            asset_folder = get_path_from_map(module, 'assetFolder', 'assets')

            is_library_project = module['isAndroidLibraryProject']
            android_params = {
                'android_manifest': android_manifest,
                'res': res_folder,
                'asset_folder': asset_folder,
                'is_android_library_project': str(is_library_project).lower(),
                'run_proguard': 'false',
                'module_gen_path':  get_path_from_map(module, 'moduleGenPath'),
                'proguard_config': '/proguard.cfg',
                'keystore': keystore,
                'libs_path': '/%s' % module.get('nativeLibs', 'libs'),
                'apk_path': get_path_from_map(module, 'binaryPath'),
            }

            if android_auto_generation_disabled:
                android_params['enable_sources_autogeneration'] = 'false'
            else:
                android_params['enable_sources_autogeneration'] = 'true'

            xml += ANDROID_FACET % android_params

        # Source code and libraries component.
        xml += '\n  <component name="NewModuleRootManager" inherit-compiler-output="true">'

        # Empirically, if there are multiple source folders, then the
        # <content> element for the buck-out/android/gen folder should be
        # listed before the other source folders.
        num_source_folders = len(module['sourceFolders'])
        if num_source_folders > 1 and module['hasAndroidFacet']:
            xml = add_buck_android_source_folder(xml, module)

        # Source folders.
        xml += '\n    <content url="file://$MODULE_DIR$">'
        for source_folder in module['sourceFolders']:
            if 'packagePrefix' in source_folder:
                package_prefix = 'packagePrefix="%s" ' % source_folder['packagePrefix']
            else:
                package_prefix = ''
            if source_folder['isResource']:
                resource_folder_type = 'java-test-resource' if source_folder['isTestSource'] else 'java-resource'
                xml += '\n      <sourceFolder url="%(url)s" type="%(resource_folder_type)s" %(package_prefix)s/>' % {
                    'url': source_folder['url'],
                    'resource_folder_type': resource_folder_type,
                    'package_prefix': package_prefix
                }
            else:
                xml += '\n      <sourceFolder url="%(url)s" isTestSource="%(is_test_source)s" %(package_prefix)s/>' % {
                    'url': source_folder['url'],
                    'is_test_source': str(source_folder['isTestSource']).lower(),
                    'package_prefix': package_prefix
                }
        for exclude_folder in module['excludeFolders']:
            xml += '\n      <excludeFolder url="%s" />' % exclude_folder['url']
        for exclude_folder in sorted(additional_excludes[module['pathToImlFile']]):
            normalized_dir = os.path.dirname(os.path.normpath(
                module['pathToImlFile']))
            xml += '\n      <excludeFolder url="file://$MODULE_DIR$/%s" />' % os.path.relpath(exclude_folder, normalized_dir)
        xml += '\n    </content>'

        xml = add_annotation_generated_source_folder(xml, module)

        # Empirically, if there is one source folder, then the <content>
        # element for the buck-out/android/gen folder should be listed after
        # the other source folders.
        if num_source_folders <= 1 and module['hasAndroidFacet']:
            xml = add_buck_android_source_folder(xml, module)

        # Dependencies.
        dependencies = module['dependencies']
        module_name = module['name']

        # We need to filter out some of the modules in the dependency list:
        # (1) The module may list itself as a dependency with scope="TEST",
        #     which is bad.
        # (2) The module may list another module as a dependency with both
        #     COMPILE and TEST scopes, in which case the COMPILE scope should
        #     win.

        # compile_dependencies will be the set of names of dependent modules
        # that do not have scope="TEST"
        compile_dependencies = filter(
            lambda dep: dep['type'] == 'module' and
            ((not ('scope' in dep)) or dep['scope'] != 'TEST'),
            dependencies)
        compile_dependencies = map(
            lambda dep: dep['moduleName'], compile_dependencies)
        compile_dependencies = set(compile_dependencies)

        # Filter dependencies to satisfy (1) and (2) defined above.
        filtered_dependencies = []
        for dep in dependencies:
            if dep['type'] != 'module':
                # Non-module dependencies should still be included.
                filtered_dependencies.append(dep)
            else:
                # dep must be a module
                dep_module_name = dep['moduleName']
                if dep_module_name == module_name:
                    # Exclude self-references!
                    continue
                elif 'scope' in dep and dep['scope'] == 'TEST':
                    # If this is a scope="TEST" module and the module is going
                    # to be included as a scope="COMPILE" module, then exclude
                    # it.
                    if not (dep_module_name in compile_dependencies):
                        filtered_dependencies.append(dep)
                else:
                    # Non-test modules should still be included.
                    filtered_dependencies.append(dep)

        # Now that we have filtered the dependencies, we can convert the
        # remaining ones directly into XML.
        excluded_deps_names = set()

        if module_type == 'PLUGIN_MODULE':
            # all the jars below are parts of IntelliJ SDK and even though they
            # are required for language plugins to work standalone, they cannot
            # be included as the plugin module dependency because they would
            # clash with IntelliJ
            excluded_deps_names = set([
                'annotations',    # org/intellij/lang/annotations, org/jetbrains/annotations
                'extensions',     # com/intellij/openapi/extensions/
                'idea',           # org/intellij, com/intellij
                'jdom',           # org/jdom
                'junit',          # junit/
                'light_psi_all',  # light psi library
                'openapi',        # com/intellij/openapi
                'picocontainer',  # org/picocontainer
                'trove4j',        # gnu/trove
                'util',           # com/intellij/util
            ])

        for dep in filtered_dependencies:
            if 'scope' in dep:
                dep_scope = 'scope="%s" ' % dep['scope']
            else:
                dep_scope = ''

            dep_type = dep['type']
            if dep_type == 'library':
                if dep['name'] in excluded_deps_names:
                    continue

                xml += '\n    <orderEntry type="library" exported="" %sname="%s" level="project" />' % (dep_scope, dep['name'])
            elif dep_type == 'module':
                dep_module_name = dep['moduleName']

                # TODO(mbolin): Eliminate this special-case for jackson. It
                # exists because jackson is not an ordinary module: it is a
                # module that functions as a library. Project.java should add
                # it as such in project.json to eliminate this special case.
                if dep_module_name == 'module_first_party_orca_third_party_jackson':
                    exported = 'exported="" '
                else:
                    exported = ''
                xml += '\n    <orderEntry type="module" module-name="%s" %s%s/>' % (dep_module_name, exported, dep_scope)
            elif dep_type == 'inheritedJdk':
                xml += '\n    <orderEntry type="inheritedJdk" />'
            elif dep_type == 'jdk':
                jdkName = dep.get('jdkName', '1.7')
                jdkType = dep.get('jdkType', 'JavaSDK')
                xml += '\n    <orderEntry type="jdk" jdkName="%s" jdkType="%s" />' % (jdkName, jdkType)
            elif dep_type == 'sourceFolder':
                xml += '\n    <orderEntry type="sourceFolder" forTests="false" />'

        # Close source code and libraries component.
        xml += '\n  </component>'

        # Close XML.
        xml += MODULE_XML_END

        # Write the module to a file.
        write_file_if_changed(module['pathToImlFile'], xml)


def add_buck_android_source_folder(xml, module):
    # Apparently if we write R.java and friends to a gen/ directory under
    # buck-out/android/ then IntelliJ wants that to be included as a separate
    # source root.
    if 'moduleGenPath' in module:
        xml += '\n    <content url="file://$MODULE_DIR$/%s">' % module['moduleGenPath']
        xml += '\n      <sourceFolder url="file://$MODULE_DIR$/%s" isTestSource="false" />'\
               % module['moduleGenPath']
        xml += '\n    </content>'
    if 'moduleRJavaPath' in module:
        xml += '\n    <content url="file://$MODULE_DIR$/%s">' % module['moduleRJavaPath']
        xml += '\n      <sourceFolder '
        xml += 'url="file://$MODULE_DIR$/%s" ' % module['moduleRJavaPath']
        xml += 'isTestSource="false" />'
        xml += '\n    </content>'
    return xml


def add_annotation_generated_source_folder(xml, module):
    if 'annotationGenPath' in module:
        annotation_gen_is_for_test = ('annotationGenIsForTest' in module and
                                      module['annotationGenIsForTest'])
        is_test_source = str(annotation_gen_is_for_test).lower()

        xml += '\n    <content url="file://$MODULE_DIR$/%s">' % module['annotationGenPath']
        xml += '\n      <sourceFolder url="file://$MODULE_DIR$/%s" isTestSource="%s" />'\
               % (module['annotationGenPath'], is_test_source)
        xml += '\n    </content>'
    return xml


def write_all_modules(modules):
    """Writes a modules.xml file that defines all of the modules in the project."""
    # Build up the XML.
    xml = ALL_MODULES_XML_START

    # Alpha-sort modules by path before writing them out.
    # This ensures that the ordering within modules.xml is stable.
    modules.sort(key=lambda module: module['pathToImlFile'])

    for module in modules:
        relative_path = module['pathToImlFile']
        xml += '\n      <module fileurl="file://$PROJECT_DIR$/%s" filepath="$PROJECT_DIR$/%s" %s/>' % (
            relative_path,
            relative_path,
            'group="modules"' if not module['isRootModule'] else '')
    xml += ALL_MODULES_XML_END

    # Write the modules to a file.
    write_file_if_changed('.idea/modules.xml', xml)


def write_misc_file(java_settings):
    """Writes a misc.xml file to define some settings specific to the project."""
    output_url = '<output url="file://$PROJECT_DIR$/' + \
        java_settings.get('outputUrl', 'build-ij/classes') + '" />'
    xml = MISC_XML % {
        'java_language_level': java_settings.get('languageLevel', 'JDK_1_6'),
        'project_jdk_name': java_settings.get('jdkName', 'Android API 21 Platform'),
        'project_jdk_type': java_settings.get('jdkType', 'Android SDK'),
        'project_output_url': output_url
    }

    write_file_if_changed('.idea/misc.xml', xml)


def write_aars(aars):
    """Writes an XML file to define each prebuilt aar."""
    mkdir_p('.idea/libraries')
    for aar in aars:
        # Build up the XML.
        name = aar['name']
        xml = AAR_XML_START % {
            'name': name,
            'binary_jar': aar['jar'],
        }

        if 'res' in aar:
            xml += AAR_XML_RESOURCE % {'resource_path': aar['res']}
        if 'assets' in aar:
            xml += AAR_XML_RESOURCE % {'resource_path': aar['assets']}

        xml += AAR_XML_END

        # Write the library to a file
        write_file_if_changed('.idea/libraries/%s.xml' % name, xml)


def write_libraries(libraries):
    """Writes an XML file to define each library."""
    mkdir_p('.idea/libraries')
    for library in libraries:
        # Build up the XML.
        name = library['name']
        xml = LIBRARY_XML_START % {
            'name': name,
            'binary_jar': library['binaryJar'],
        }

        if 'javadocUrl' in library:
            xml += LIBRARY_XML_WITH_JAVADOC % {
                'javadoc_url': library['javadocUrl'],
            }
        else:
            xml += LIBRARY_XML_NO_JAVADOC

        if 'sourceJar' in library:
            xml += LIBRARY_XML_WITH_SOURCES % {
                'source_jar': library['sourceJar'],
            }
        else:
            xml += LIBRARY_XML_NO_SOURCES

        xml += LIBRARY_XML_END

        # Write the library to a file
        write_file_if_changed('.idea/libraries/%s.xml' % name, xml)


def write_run_configs():
    """Writes the run configurations that should be available"""
    mkdir_p('.idea/runConfigurations')

    xml = RUN_CONFIG_XML_START
    xml += REMOTE_RUN_CONFIG_XML % {'name': "Debug Buck test"}
    xml += RUN_CONFIG_XML_END
    write_file_if_changed('.idea/runConfigurations/Debug_Buck_test.xml', xml)


def write_file_if_changed(path, content):
    PROJECT_FILES.add(path)
    if os.path.exists(path):
        file_content_as_string = open(path, 'r').read()
        needs_update = content.strip() != file_content_as_string.strip()
    else:
        needs_update = True
    if needs_update:
        out = open(path, 'wb')
        out.write(content)
        MODIFIED_FILES.append(path)


def mkdir_p(path):
    """Runs the equivalent of `mkdir -p`
    Taken from http://stackoverflow.com/questions/600268/mkdir-p-functionality-in-python
    Args:
      path: an absolute path
    """
    try:
        os.makedirs(path)
    except OSError as exc:
        if exc.errno == errno.EEXIST:
            pass
        else:
            raise


def clean_old_files():
    if os.path.isdir('.git'):
        try:
            files_to_clean = subprocess.check_output([
                'git',
                'ls-files',
                '--other'])
            for file_name in files_to_clean.splitlines():
                if (file_name.endswith('.iml') and
                        file_name not in PROJECT_FILES):
                    os.remove(file_name)
            return
        except Exception as e:
            pass


if __name__ == '__main__':
    json_file = sys.argv[1]
    generate_minimum_project = False
    android_auto_generation_disabled = False

    for key in sys.argv[2:]:
        if key == '--generate_minimum_project':
            generate_minimum_project = True
        if key == '--disable_android_auto_generation_setting':
            android_auto_generation_disabled = True

    parsed_json = json.load(open(json_file, 'r'))

    libraries = parsed_json['libraries']
    write_libraries(libraries)

    aars = parsed_json['aars']
    write_aars(aars)

    modules = parsed_json['modules']
    write_modules(modules, generate_minimum_project, android_auto_generation_disabled)
    write_all_modules(modules)
    write_run_configs()

    java_settings = parsed_json['java']
    write_misc_file(java_settings)

    # Write the list of modified files to stdout
    for path in MODIFIED_FILES:
        print path

    print >> sys.stderr, ('  ::  Please resynchronize IntelliJ via File->Synchronize ' +
                          'or Cmd-Opt-Y (Mac) or Ctrl-Alt-Y (PC/Linux)')
