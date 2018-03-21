/*
 * Copyright 2018-present Facebook, Inc.
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

package com.facebook.buck.tools.documentation.generator;

import java.io.File;
import org.kohsuke.args4j.Option;

/** Metadata describing command line options accepted by the generator binary. */
public class CliArgs {

  @Option(name = "--help", usage = "Show this help")
  boolean showHelp = false;

  @Option(
    name = "--destination_directory",
    usage = "Destination directory for generated template files."
  )
  File destinationDirectory;

  @Option(name = "--skylark_package", usage = "Java package where Skylark functions are defined.")
  String skylarkPackage = "com.facebook.buck.skylark.function";
}
