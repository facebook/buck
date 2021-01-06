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

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import java.util.Collections;
import java.util.List;

/** Adds the class name itself to the list of names being searched */
public class DefaultSearchableClassNameContributor
    extends BuckAutoDepsSearchableClassNameContributor {

  @Override
  public boolean isApplicable(Project project, String className) {
    return true;
  }

  @Override
  public List<String> getSearchableClassNames(Project project, String className) {
    return Collections.singletonList(className);
  }

  @Override
  public boolean filter(Project project, String className, PsiClass psiClass) {
    return true;
  }
}
