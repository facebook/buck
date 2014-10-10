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

package com.facebook.buck.apple;

import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.shell.Genrule;
import com.google.common.base.Function;
import com.google.common.base.Optional;

import java.nio.file.Path;
import java.util.List;

/**
 * Runs a shell script after the 'copy resources' build step has run.
 * <p>
 * Example rule:
 * <pre>
 * ios_postprocess_resources(
 *   name = 'pngcrush',
 *   cmd = '../Tools/pngcrush.sh',
 * )
 * </pre>
 * </p>
 * This class is a hack and in the long-term should be replaced with a rule
 * which operates similarly to apk_genrule, or should be removed entirely
 * if possible. It will be necessary to replace or remove this when we begin
 * building iOS binaries with buck.
 */
public class IosPostprocessResources extends Genrule {

  IosPostprocessResources(
      BuildRuleParams params,
      SourcePathResolver resolver,
      List<SourcePath> srcs,
      Function<String, String> macroExpander,
      Optional<String> cmd,
      Optional<String> bash,
      Optional<String> cmdExe,
      String out,
      final Function<Path, Path> relativeToAbsolutePathFunction) {
    super(
        params,
        resolver,
        srcs,
        macroExpander,
        cmd,
        bash,
        cmdExe,
        out,
        relativeToAbsolutePathFunction);
  }

}
