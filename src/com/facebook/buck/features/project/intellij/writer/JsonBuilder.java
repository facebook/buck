/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij.writer;

import com.facebook.buck.features.project.intellij.IjDependencyListBuilder.DependencyEntry;
import com.facebook.buck.features.project.intellij.IjDependencyListBuilder.Scope;
import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.stream.Collectors;

/** This is a utility class to build the json data for serialization of intellij project files. */
public class JsonBuilder {

  /** Convert the data in generatedFolders into map for serialization. */
  public static Object buildGeneratedFolders(ImmutableSet<IjSourceFolder> generatedFolders) {
    return buildFolders(generatedFolders);
  }

  /**
   * Convert the data in contentRoots into map for serialization. The folders are grouped by `type`
   * which has two values `sourceFolder` and `excludeFolder`
   */
  public static Object buildContentRoots(ImmutableList<ContentRoot> contentRoots) {
    return contentRoots.stream()
        .map(JsonBuilder::convertContentRootToMap)
        .collect(Collectors.toList());
  }

  /** The dependencies are grouped into MODULE, LIBRARY and MODULE_LIBRARY. */
  public static Object buildDependencies(ImmutableSet<DependencyEntry> dependencies) {
    return dependencies.stream()
        .collect(
            Collectors.groupingBy(
                dep -> dep.getType(),
                Collectors.mapping(
                    dep -> {
                      JsonMap map = new JsonMap();

                      if (dep.getModule() != null) {
                        addDependencyFields(
                            map,
                            dep.getModule().getName(),
                            dep.getModule().getExported(),
                            dep.getModule().getScope());
                      } else if (dep.getLibrary() != null) {
                        addDependencyFields(
                            map,
                            dep.getLibrary().getName(),
                            dep.getLibrary().getExported(),
                            dep.getLibrary().getScope());

                      } else if (dep.getModuleLibrary() != null) {
                        addDependencyFields(
                            map,
                            dep.getModuleLibrary().getName(),
                            dep.getModuleLibrary().getExported(),
                            dep.getModuleLibrary().getScope());
                        addLibraryFields((IjLibrary) dep.getModuleLibrary().getElement(), map);
                      }
                      return map.get();
                    },
                    Collectors.toList())));
  }

  private static Object convertContentRootToMap(ContentRoot contentRoot) {
    return new JsonMap()
        .put("url", contentRoot.getUrl())
        .put("folders", buildFolders(contentRoot.getFolders()))
        .get();
  }

  private static Object buildFolders(Collection<IjSourceFolder> folders) {
    return folders.stream()
        .collect(
            Collectors.groupingBy(
                folder -> folder.getType(),
                Collectors.mapping(JsonBuilder::convertFolderToMap, Collectors.toList())));
  }

  private static Object convertFolderToMap(IjSourceFolder folder) {
    return new JsonMap()
        .put("url", folder.getUrl())
        .put("isTestSource", folder.getIsTestSource())
        .put("packagePrefix", folder.getPackagePrefix())
        .put("resourceFolderType", folder.getIjResourceFolderType().toString())
        .put("relativeOutputPath", folder.getRelativeOutputPath())
        .get();
  }

  private static void addDependencyFields(JsonMap map, String name, boolean exported, Scope scope) {
    map.put("name", name).put("exported", exported);
    // We assume the default value as COMPILE and skip writing thereby reducing json file size.
    if (scope != Scope.COMPILE) {
      map.put("scope", scope);
    }
  }

  /** Extract the fields from IjLibrary and put into map. */
  public static void addLibraryFields(IjLibrary lib, JsonMap map) {
    map.put(
        "binaryJars",
        lib.getBinaryJars().stream().map(Object::toString).collect(Collectors.toList()));

    map.put(
        "classPaths",
        lib.getClassPaths().stream().map(Object::toString).collect(Collectors.toList()));

    map.put("javadocUrls", lib.getJavadocUrls());

    map.put(
        "sourceJars",
        lib.getSourceJars().stream().map(Object::toString).collect(Collectors.toList()));
  }
}
