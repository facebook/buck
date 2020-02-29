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

package com.facebook.buck.intellij.ideabuck.autodeps;

import com.intellij.openapi.extensions.ProjectExtensionPointName;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import java.util.List;

/**
 * This extension point allows other plugins to provide additional class names to be searched for
 * autodeps.
 */
public abstract class BuckAutoDepsSearchableClassNameContributor {
  public static ProjectExtensionPointName<BuckAutoDepsSearchableClassNameContributor> EP_NAME =
      new ProjectExtensionPointName<>(
          "intellij.buck.plugin.BuckAutoDepsSearchableClassNameContributor");

  /** Whether the current implementation is applicable for the project and the class name */
  public abstract boolean isApplicable(Project project, String className);

  /** Get a list of additional searchable class names. Do not include the className itself. */
  public abstract List<String> getSearchableClassNames(Project project, String className);

  /**
   * Filter out incorrect search results. This method should return true if psiClass should be kept
   */
  public abstract boolean filter(Project project, String className, PsiClass psiClass);
}
