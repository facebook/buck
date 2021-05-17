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

package com.facebook.buck.jvm.kotlin.plugin

import com.intellij.mock.MockProject
import org.jetbrains.kotlin.compiler.plugin.ComponentRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.resolve.jvm.extensions.AnalysisHandlerExtension

class DependencyTrackerRegistrar : ComponentRegistrar {

  override fun registerProjectComponents(
      project: MockProject,
      configuration: CompilerConfiguration
  ) {
    val outputPath = configuration[DependencyTrackerCommandLineProcessor.ARG_OUTPUT_PATH]

    AnalysisHandlerExtension.registerExtension(
        project,
        DependencyTrackerExtension(
            checkNotNull(outputPath) { "Please provide output path via 'out' option" }))
  }
}
