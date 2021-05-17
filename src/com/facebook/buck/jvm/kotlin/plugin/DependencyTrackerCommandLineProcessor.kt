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

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

class DependencyTrackerCommandLineProcessor : CommandLineProcessor {

  companion object {

    private const val OPTION_OUTPUT_PATH = "out"

    val ARG_OUTPUT_PATH = CompilerConfigurationKey<String>(OPTION_OUTPUT_PATH)
  }

  override val pluginId: String = "buck_deps_tracker"

  override val pluginOptions: Collection<AbstractCliOption> =
      listOf(
          CliOption(
              optionName = OPTION_OUTPUT_PATH,
              valueDescription = "[string]",
              description = "Absolute path to output JSON file",
              required = true))

  override fun processOption(
      option: AbstractCliOption,
      value: String,
      configuration: CompilerConfiguration
  ) {
    when (option.optionName) {
      OPTION_OUTPUT_PATH -> configuration.put(ARG_OUTPUT_PATH, value)
      else -> throw CliOptionProcessingException("Unknown option: ${option.optionName}")
    }
  }
}
