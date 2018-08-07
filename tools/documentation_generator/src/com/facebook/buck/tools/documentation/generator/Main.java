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

import com.facebook.buck.tools.documentation.generator.skylark.SignatureCollector;
import com.facebook.buck.tools.documentation.generator.skylark.rendering.SoyTemplateSkylarkSignatureRenderer;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.skylarkinterface.SkylarkCallable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Entry point of the documentation generator binary.
 *
 * <p>Executing this class produces a function Soy template for each field in a classpath annotated
 * by {@link SkylarkCallable} and a table of contents that lists all of them.
 *
 * <p>To use, make sure that the target with all Skylark functions is included as a dependency of
 * //tools/documentation_generator/src/com/facebook/buck/tools/documentation/generator:main and run:
 *
 * <pre>
 * buck run //tools/documentation_generator/src/com/facebook/buck/tools/documentation/generator:generator \
 *   -- --destination_directory path/to/buck/docs/skylark/generated
 * </pre>
 *
 * By default all packages inside of com.facebook.buck.skylark.function package are scanned, but
 * {@code --skylark_package} command line flag can be used to specify any other package.
 */
public class Main {

  /** Executable entry point. */
  public static void main(String[] args) throws Exception {
    CliArgs parsedArgs = new CliArgs();
    CmdLineParser cmdLineParser = new CmdLineParser(parsedArgs);
    cmdLineParser.parseArgument(args);

    SoyTemplateSkylarkSignatureRenderer renderer = new SoyTemplateSkylarkSignatureRenderer();

    ImmutableList<SkylarkCallable> skylarkSignatures =
        SignatureCollector.getSkylarkCallables(
                classInfo -> classInfo.getPackageName().contains(parsedArgs.skylarkPackage))
            .collect(ImmutableList.toImmutableList());

    Path destinationPath = parsedArgs.destinationDirectory.toPath();
    Path tableOfContentsPath = destinationPath.resolve("__table_of_contents.soy");
    String tableOfContents = renderer.renderTableOfContents(skylarkSignatures);
    Files.write(tableOfContentsPath, tableOfContents.getBytes(StandardCharsets.UTF_8));
    for (SkylarkCallable signature : skylarkSignatures) {
      Path functionPath = destinationPath.resolve(signature.name() + ".soy");
      String functionContent = renderer.render(signature);
      Files.write(functionPath, functionContent.getBytes(StandardCharsets.UTF_8));
    }
  }
}
