/*
 * Copyright 2015-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.java.intellij;

import com.facebook.buck.java.JavaPackageFinder;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.nio.file.Path;
import java.util.List;

/**
 * Top-level class for IntelliJ project generation.
 */
public class IjProject {

  private final IjModuleGraph moduleGraph;
  private final JavaPackageFinder javaPackageFinder;

  public IjProject(
      IjModuleGraph moduleGraph,
      JavaPackageFinder javaPackageFinder) {
    this.moduleGraph = moduleGraph;
    this.javaPackageFinder = javaPackageFinder;
  }

  /**
   * @return list of prebuilt libraries (currently .jar and .aar) for the project. These correspond
   * to the .idea/libraries files.
   *
   * The use of SerializableAndroidAar is a massive hack. If you look at intellij.py it turns out
   * SerializableAndroidAar will generate the exact same XML as SerializableAndroidJar if the
   * res/assets are not specified.
   */
  public ImmutableList<SerializableAndroidAar> getSerializedLibrariesDescription() {
    ImmutableSet.Builder<SerializableAndroidAar> serializableLibrariesBuilder =
        new ImmutableSet.Builder<>();
    for (IjModule module: moduleGraph.getNodes()) {
      for (IjLibrary library : module.getLibraries()) {
        SerializableAndroidAar serializableAndroidLibrary = new SerializableAndroidAar(
            library.getName(),
            null,
            null,
            library.getBinaryJar());
        serializableLibrariesBuilder.add(serializableAndroidLibrary);
      }
    }
    return serializableLibrariesBuilder.build().asList();
  }

  /**
   * @return a list of all of the modules for the given project. These correspond to the .iml files.
   */
  public ImmutableList<SerializableModule> getSerializedProjectDescription() {
    ImmutableSet.Builder<SerializableModule> serializableModuleListBuilder =
        new ImmutableSet.Builder<>();
    for (IjModule module: moduleGraph.getNodes()) {
      SerializableModule serializedModule = new SerializableModule();

      serializedModule.name = module.getModuleName();
      serializedModule.pathToImlFile = module.getModuleImlFilePath();
      serializedModule.sourceFolders = toSourceFolders(
          module.getFolders(),
          javaPackageFinder.findJavaPackage(module.getModuleBasePath().resolve("removed")),
          module.getModuleBasePath());
      serializedModule.moduleGenPath = module.getRelativeGenPath();
      serializedModule.dependencies =
          FluentIterable.from(Iterables.concat(
              toDependentModules(moduleGraph.getOutgoingNodesFor(module)),
              librariesToDependentModules(module.getLibraries()),
              ImmutableList.of(DependentModule.newInheritedJdk())
          )).toList();
      serializedModule.isRootModule = module.getModuleBasePath().toString().isEmpty();

      serializedModule.hasAndroidFacet = false;
      serializedModule.isAndroidLibraryProject = false;
      serializedModule.isIntelliJPlugin = false;

      serializableModuleListBuilder.add(serializedModule);
    }
    return serializableModuleListBuilder.build().asList();
  }

  /**
   * @param path path to folder.
   * @param moduleBasePath path to the location of the .iml file.
   * @return a path, relative to the module .iml file location describing a folder
   * in IntelliJ format.
   */
  private String toModuleDirRelativeString(Path path, Path moduleBasePath) {
    String moduleRelativePath = moduleBasePath.relativize(path).toString();
    if (moduleRelativePath.isEmpty()) {
      return "file://$MODULE_DIR$";
    } else {
      return "file://$MODULE_DIR$/" + moduleRelativePath;
    }
  }

  /**
   * @param folders collection of folders to convert.
   * @param packagePrefix the package prefix for the module containing the folders.
   * @param moduleBasePath path to the location of the .iml file for the module containing the
   *                       folders.
   * @return list of folders in a format expected by the intellij.py script.
   */
  private List<SerializableModule.SourceFolder> toSourceFolders(
      ImmutableCollection<IjFolder> folders,
      final String packagePrefix,
      final Path moduleBasePath) {
    ImmutableList<SerializableModule.SourceFolder> sourceFolders = FluentIterable.from(folders)
        .filter(
            new Predicate<IjFolder>() {
              @Override
              public boolean apply(IjFolder input) {
                return input.getType() != IjFolder.Type.EXCLUDE_FOLDER;
              }
            })
        .transform(
            new Function<IjFolder, SerializableModule.SourceFolder>() {
              @Override
              public SerializableModule.SourceFolder apply(IjFolder input) {
                return new SerializableModule.SourceFolder(
                    toModuleDirRelativeString(input.getPath(), moduleBasePath),
                    input.isTest(),
                    input.getWantsPackagePrefix() ? packagePrefix : null);
              }
            })
        .toList();
    return Lists.newArrayList(sourceFolders);
  }

  /**
   * Convert modules into a format used by IntelliJ for dependencies. This only processes actual
   * modules (which have a coresponding .iml file).
   *
   * @param modules list of modules to convert.
   * @return list of modules in a format expected by the intellij.py script.
   */
  private List<SerializableDependentModule> toDependentModules(
      ImmutableCollection<IjModule> modules) {
    ImmutableList<SerializableDependentModule> dependentModules = FluentIterable.from(modules)
        .transform(
            new Function<IjModule, SerializableDependentModule>() {
              @Override
              public SerializableDependentModule apply(IjModule input) {
                SerializableDependentModule module = new SerializableDependentModule("module");
                module.forTests = false;
                module.moduleName = input.getModuleName();
                // TODO(mkosiba): all modules are non-test and 'PROVIDED'. This works fine for
                //                indexing purposes but the resulting project is not buildable.
                module.scope = "PROVIDED";
                return module;
              }
            })
        .toList();
    return Lists.newArrayList(dependentModules);
  }

  /**
   * Convert libraries into a format used by IntelliJ for dependencies.
   *
   * @param libraries list of libraries to convert.
   * @return list of libraries in a format expected by the intellij.py script.
   */
  private List<SerializableDependentModule> librariesToDependentModules(
      ImmutableCollection<IjLibrary> libraries) {
    ImmutableList<SerializableDependentModule> dependentLibraries = FluentIterable.from(libraries)
        .transform(
            new Function<IjLibrary, SerializableDependentModule>() {
              @Override
              public SerializableDependentModule apply(IjLibrary input) {
                SerializableDependentModule module = new SerializableDependentModule("library");
                module.forTests = false;
                module.name = input.getName();
                module.scope = "PROVIDED";
                return module;
              }
            })
        .toList();
    return Lists.newArrayList(dependentLibraries);
  }
}
