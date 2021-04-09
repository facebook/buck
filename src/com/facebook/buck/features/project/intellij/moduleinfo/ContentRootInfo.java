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

package com.facebook.buck.features.project.intellij.moduleinfo;

import com.facebook.buck.core.util.immutables.BuckStyleValue;
import java.util.List;

/** URLs inside a content root, including sourceFolders and excludeFolders */
@BuckStyleValue
public abstract class ContentRootInfo {
  public abstract String getUrl();

  public abstract List<String> getRelativeSourceFolders();

  public abstract List<String> getRelativeExcludeFolders();

  public static ContentRootInfo of(
      String url, List<String> relativeSourceFolders, List<String> relativeExcludeFolders) {
    return ImmutableContentRootInfo.ofImpl(url, relativeSourceFolders, relativeExcludeFolders);
  }
}
