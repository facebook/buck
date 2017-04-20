/*
 * Copyright 2014-present Facebook, Inc.
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

package org.openqa.selenium.buck.javascript;

import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.shell.ShellStep;
import com.facebook.buck.step.ExecutionContext;
import com.google.common.collect.ImmutableList;

import java.nio.file.Path;

class JavascriptFragmentStep extends ShellStep {

  private final SourcePathResolver resolver;
  private final Tool compiler;
  private final Iterable<Path> jsDeps;
  private final Path temp;
  private final Path output;
  private final ImmutableList<String> flags;

  public JavascriptFragmentStep(
      Path workingDirectory,
      SourcePathResolver resolver,
      Tool compiler,
      ImmutableList<String> flags,
      Path temp,
      Path output,
      Iterable<Path> jsDeps) {
    super(workingDirectory);
    this.resolver = resolver;
    this.compiler = compiler;
    this.flags = flags;
    this.temp = temp;
    this.output = output;
    this.jsDeps = jsDeps;
  }

  @Override
  protected ImmutableList<String> getShellCommandInternal(ExecutionContext context) {
    ImmutableList.Builder<String> cmd = ImmutableList.builder();

    String wrapper = "function(){%output%; return this._.apply(null,arguments);}";
    wrapper = String.format(
        "function(){return %s.apply({" +
            "navigator:typeof window!='undefined'?window.navigator:null," +
            "document:typeof window!='undefined'?window.document:null" +
            "}, arguments);}", wrapper);

    cmd.addAll(compiler.getCommandPrefix(resolver));
    cmd.add(
        String.format("--js_output_file='%s'", output),
        String.format("--output_wrapper='%s'", wrapper),
        "--compilation_level=ADVANCED_OPTIMIZATIONS",
        "--define=goog.NATIVE_ARRAY_PROTOTYPES=false",
        "--define=bot.json.NATIVE_JSON=false");
    cmd.addAll(flags);
    cmd.add(
        "--jscomp_off=unknownDefines",
        "--jscomp_off=deprecated",
        "--jscomp_error=accessControls",
        "--jscomp_error=ambiguousFunctionDecl",
        "--jscomp_error=checkDebuggerStatement",
        "--jscomp_error=checkRegExp",
        "--jscomp_error=checkTypes",
        "--jscomp_error=checkVars",
        "--jscomp_error=const",
        "--jscomp_error=constantProperty",
        "--jscomp_error=duplicate",
        "--jscomp_error=duplicateMessage",
        "--jscomp_error=externsValidation",
        "--jscomp_error=fileoverviewTags",
        "--jscomp_error=globalThis",
        "--jscomp_error=internetExplorerChecks",
        "--jscomp_error=invalidCasts",
        "--jscomp_error=missingProperties",
        "--jscomp_error=nonStandardJsDocs",
        "--jscomp_error=strictModuleDepCheck",
        "--jscomp_error=typeInvalidation",
        "--jscomp_error=undefinedNames",
        "--jscomp_error=undefinedVars",
        "--jscomp_error=uselessCode",
        "--jscomp_error=visibility"
    );

    for (Path dep : jsDeps) {
      cmd.add("--js='" + dep + "'");
      if (dep.endsWith("closure/goog/base.js")) {
        // Always load closure's deps.js first to "forward declare" all of the Closure types. This
        // prevents type errors when a symbol is referenced in a file's type annotation, but not
        // actually needed in the compiled output.
        cmd.add("--js='third_party/closure/goog/deps.js'");
      }
    }
    cmd.add("--js='" + temp + "'");

    return cmd.build();
  }

  @Override
  public String getShortName() {
    return "compile js fragment";
  }
}
