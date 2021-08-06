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

package com.facebook.buck.features.project.intellij.lang.python;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.core.rules.DescriptionWithTargetGraph;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.features.project.intellij.BaseIjModuleRule;
import com.facebook.buck.features.project.intellij.ModuleBuildContext;
import com.facebook.buck.features.project.intellij.model.IjModuleFactoryResolver;
import com.facebook.buck.features.project.intellij.model.IjModuleType;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.features.python.PythonLibraryDescription;
import com.facebook.buck.features.python.PythonLibraryDescriptionArg;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import java.nio.file.Path;

/** Support for transforming a python_library declaration to an intellij module */
public class PythonLibraryModuleRule extends BaseIjModuleRule<PythonLibraryDescriptionArg> {
  private static final Logger LOG = Logger.get(PythonLibraryModuleRule.class);

  public PythonLibraryModuleRule(
      ProjectFilesystem projectFilesystem,
      IjModuleFactoryResolver moduleFactoryResolver,
      IjProjectConfig projectConfig) {
    super(projectFilesystem, moduleFactoryResolver, projectConfig);
  }

  @Override
  public Class<? extends DescriptionWithTargetGraph<?>> getDescriptionClass() {
    return PythonLibraryDescription.class;
  }

  @Override
  public void apply(TargetNode<PythonLibraryDescriptionArg> target, ModuleBuildContext context) {
    addDepsAndSources(target, false /* wantsPackagePrefix */, context);
  }

  @Override
  public IjModuleType detectModuleType(TargetNode<PythonLibraryDescriptionArg> targetNode) {
    return IjModuleType.PYTHON_MODULE;
  }

  /**
   * For python modules, we need to fix the module path by trimming the module path with the
   * "base_module" property of the rule. For example, if the module path is foo/bar/baz, and the
   * "base_module" of the rule is "bar.baz", the adjusted module path will be foo. If they don't
   * match, we just return the original module path.
   */
  @Override
  public Path adjustModulePath(
      TargetNode<PythonLibraryDescriptionArg> targetNode, Path modulePath) {
    String baseModule = targetNode.getConstructorArg().getBaseModule().orElse(null);
    if (baseModule == null || baseModule.isEmpty()) {
      return modulePath;
    }
    String[] components = baseModule.split("\\.");
    int index = components.length - 1;
    Path adjustedModulePath = modulePath;
    while (index >= 0) {
      if (components[index].equals(adjustedModulePath.getFileName().toString())) {
        adjustedModulePath = adjustedModulePath.getParent();
        index--;
      } else {
        LOG.warn("Base module \"" + baseModule + "\" doesn't match the path: " + modulePath);
        // Python plugin doesn't support package prefix so we don't have a better choice
        return modulePath;
      }
    }
    return adjustedModulePath;
  }
}
