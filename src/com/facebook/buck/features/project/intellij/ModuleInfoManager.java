/*
 * Copyright (c) Facebook, Inc. and its affiliates.
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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.features.project.intellij.model.ContentRoot;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.folders.ExcludeFolder;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.features.project.intellij.model.folders.InclusiveFolder;
import com.facebook.buck.features.project.intellij.moduleinfo.ContentRootInfo;
import com.facebook.buck.features.project.intellij.moduleinfo.ModuleInfo;
import com.facebook.buck.features.project.intellij.moduleinfo.ModuleInfoBinaryIndex;
import com.facebook.buck.util.types.Pair;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/** A class to manage {@link ModuleInfoBinaryIndex} */
public final class ModuleInfoManager {
  private final IjProjectConfig projectConfig;
  private final Queue<ModuleInfo> moduleInfoQueue = new ConcurrentLinkedQueue<>();
  private final ModuleInfoBinaryIndex moduleInfoBinaryIndex;
  private final IJProjectCleaner cleaner;

  public ModuleInfoManager(IjProjectConfig projectConfig, IJProjectCleaner cleaner) {
    this.projectConfig = projectConfig;
    this.moduleInfoBinaryIndex =
        new ModuleInfoBinaryIndex(projectConfig.getProjectPaths().getIdeaConfigDir());
    this.cleaner = cleaner;
  }

  /** Add a {@link ModuleInfo} to be written */
  public void addModuleInfo(
      String moduleName,
      String moduleDir,
      ImmutableSet<IjDependencyListBuilder.DependencyEntry> dependencies,
      ImmutableList<ContentRoot> contentRoots) {
    if (projectConfig.isGeneratingModuleInfoBinaryIndexEnabled()) {
      moduleInfoQueue.add(createModuleInfo(moduleName, moduleDir, dependencies, contentRoots));
    }
  }

  /** Write {@link ModuleInfoBinaryIndex} with moduleInfoQueue */
  public void write() throws IOException {
    if (projectConfig.isGeneratingModuleInfoBinaryIndexEnabled()) {
      moduleInfoBinaryIndex.write(moduleInfoQueue);
      cleaner.doNotDelete(moduleInfoBinaryIndex.getStoragePath());
    }
  }

  /** Update {@link ModuleInfoBinaryIndex} with moduleInfoQueue */
  public void update() throws IOException {
    if (projectConfig.isGeneratingModuleInfoBinaryIndexEnabled()) {
      moduleInfoBinaryIndex.update(moduleInfoQueue);
      cleaner.doNotDelete(moduleInfoBinaryIndex.getStoragePath());
    }
  }

  /** Create {@link ModuleInfo} from the information obtained during {@link IjProjectWriter} */
  private static ModuleInfo createModuleInfo(
      String moduleName,
      String moduleDir,
      ImmutableSet<IjDependencyListBuilder.DependencyEntry> dependencies,
      ImmutableList<ContentRoot> contentRoots) {
    List<String> moduleDependencies =
        dependencies.stream()
            .map(
                dep -> {
                  IjDependencyListBuilder.DependencyEntryData module = dep.getModule();
                  if (module == null) {
                    return null;
                  } else {
                    return module.getName();
                  }
                })
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    ImmutableList.Builder<ContentRootInfo> contentRootInfoBuilder = ImmutableList.builder();
    for (ContentRoot contentRoot : contentRoots) {
      String url = contentRoot.getUrl();
      ImmutableList.Builder<String> sourceFolders = ImmutableList.builder();
      ImmutableList.Builder<String> excludeFolders = ImmutableList.builder();
      for (IjSourceFolder folder : contentRoot.getFolders()) {
        Pair<String, Boolean> relativeUrlAndType = extraFolderRelativeUrlAndType(url, folder);
        if (relativeUrlAndType != null) {
          if (relativeUrlAndType.getSecond()) {
            sourceFolders.add(relativeUrlAndType.getFirst());
          } else {
            excludeFolders.add(relativeUrlAndType.getFirst());
          }
        }
      }
      contentRootInfoBuilder.add(
          ContentRootInfo.of(url, sourceFolders.build(), excludeFolders.build()));
    }
    return ModuleInfo.of(moduleName, moduleDir, moduleDependencies, contentRootInfoBuilder.build());
  }

  @Nullable
  private static Pair<String, Boolean> extraFolderRelativeUrlAndType(
      String url, IjSourceFolder folder) {
    if (InclusiveFolder.FOLDER_IJ_NAME.equals(folder.getType())
        || ExcludeFolder.FOLDER_IJ_NAME.equals(folder.getType())) {
      Preconditions.checkState(
          folder.getUrl().startsWith(url),
          folder.getUrl() + " doesn't start with the content root url: " + url);
      String relativeUrl = folder.getUrl().substring(url.length());
      if (relativeUrl.isEmpty()) {
        relativeUrl = ".";
      }
      return new Pair<>(relativeUrl, InclusiveFolder.FOLDER_IJ_NAME.endsWith(folder.getType()));
    }
    return null;
  }
}
