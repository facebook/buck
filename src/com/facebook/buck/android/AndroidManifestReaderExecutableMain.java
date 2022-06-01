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

package com.facebook.buck.android;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

/** Main entry point for executing {@link AndroidManifestReader} calls. */
public class AndroidManifestReaderExecutableMain {

  @Option(name = "--manifest-path", required = true)
  private String manifestPath;

  @Option(name = "--target-package-output")
  private String targetPackageOutputPath;

  @Option(name = "--package-output")
  private String packageOutputPath;

  @Option(name = "--instrumentation-test-runner-output")
  private String instrumentationTestRunnerOutputPath;

  public static void main(String[] args) throws IOException {
    AndroidManifestReaderExecutableMain main = new AndroidManifestReaderExecutableMain();
    CmdLineParser parser = new CmdLineParser(main);
    try {
      parser.parseArgument(args);
      main.run();
      System.exit(0);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      parser.printUsage(System.err);
      System.exit(1);
    }
  }

  private void run() throws IOException {
    AndroidManifestReader androidManifestReader =
        DefaultAndroidManifestReader.forPath(Paths.get(manifestPath));

    if (targetPackageOutputPath != null) {
      Files.write(
          Paths.get(targetPackageOutputPath), androidManifestReader.getTargetPackage().getBytes());
    }

    if (packageOutputPath != null) {
      Files.write(Paths.get(packageOutputPath), androidManifestReader.getPackage().getBytes());
    }

    if (instrumentationTestRunnerOutputPath != null) {
      Files.write(
          Paths.get(instrumentationTestRunnerOutputPath),
          androidManifestReader.getInstrumentationTestRunner().getBytes());
    }

    System.exit(0);
  }
}
