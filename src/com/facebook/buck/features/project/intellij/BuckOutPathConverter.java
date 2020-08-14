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
import com.facebook.buck.features.project.intellij.model.IjLibrary;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.project.intellij.model.folders.IjSourceFolder;
import com.facebook.buck.util.BuckConstant;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Because the current Buck daemon is not reentrant, we need to use a separate Buck daemon to
 * generate projects so that it won't affect user-issued buck commands. The separate Buck daemon
 * needs to use a different buck-out directory but in "buck project", we want to have the ability of
 * making the generated project files to use the same buck-out directory as the default one.
 *
 * <p>This class is a hack that converts the paths to use a different buck-out path before {@link
 * IjProjectWriter} writes the content to a file.
 */
public class BuckOutPathConverter {

  private final @Nullable String replacement;

  public BuckOutPathConverter(IjProjectConfig projectConfig) {
    replacement = projectConfig.getBuckOutPathForGeneratedProjectFiles().orElse(null);
  }

  public boolean hasBuckOutPathForGeneratedProjectFiles() {
    return replacement != null;
  }

  public String convert(String s) {
    if (replacement == null) {
      return s;
    }
    return s.replace(BuckConstant.getBuckOutputPath().toString(), replacement);
  }

  public Path convert(Path path) {
    return Paths.get(convert(path.toString()));
  }

  public ContentRoot convert(ContentRoot contentRoot) {
    return ContentRoot.of(
        convert(contentRoot.getUrl()),
        contentRoot.getFolders().stream()
            .map(this::convert)
            .collect(ImmutableList.toImmutableList()));
  }

  public IjSourceFolder convert(IjSourceFolder folder) {
    return IjSourceFolder.of(
        folder.getType(),
        convert(folder.getUrl()),
        folder.getPath(),
        folder.getIsTestSource(),
        folder.getIsResourceFolder(),
        folder.getIjResourceFolderType(),
        folder.getRelativeOutputPath(),
        folder.getPackagePrefix());
  }

  public IjLibrary convert(IjLibrary library) {
    return IjLibrary.builder()
        .setName(library.getName())
        .setType(library.getType())
        .setLevel(library.getLevel())
        .setBinaryJars(
            library.getBinaryJars().stream()
                .map(this::convert)
                .collect(ImmutableSet.toImmutableSet()))
        .setClassPaths(
            library.getClassPaths().stream()
                .map(this::convert)
                .collect(ImmutableSet.toImmutableSet()))
        .setSourceJars(
            library.getSourceJars().stream()
                .map(this::convert)
                .collect(ImmutableSet.toImmutableSet()))
        .setJavadocUrls(
            library.getJavadocUrls().stream()
                .map(this::convert)
                .collect(ImmutableSet.toImmutableSet()))
        .build();
  }

  public Map<String, Object> convert(Map<String, Object> androidProperties) {
    return androidProperties.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                entry -> {
                  if (entry.getValue() instanceof String) {
                    return convert((String) entry.getValue());
                  } else if (entry.getValue() instanceof Path) {
                    return convert((Path) entry.getValue());
                  } else {
                    return entry.getValue();
                  }
                }));
  }
}
