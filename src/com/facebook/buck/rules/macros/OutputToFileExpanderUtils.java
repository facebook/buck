/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules.macros;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.model.macros.MacroException;
import com.facebook.buck.model.macros.MacroMatchResult;
import com.facebook.buck.model.macros.MacroReplacer;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.google.common.base.Joiner;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public class OutputToFileExpanderUtils {
  private OutputToFileExpanderUtils() {
    // Utility class only
  }

  public static Path getMacroPath(ProjectFilesystem projectFilesystem, BuildTarget buildTarget) {
    return BuildTargets.getScratchPath(projectFilesystem, buildTarget, "%s__macros");
  }

  public static MacroReplacer wrapReplacerWithFileOutput(
      MacroReplacer replacer, BuildTarget target, BuildRuleResolver resolver) {
    return input -> {
      try {
        Optional<BuildRule> rule = resolver.getRuleOptional(target);
        if (!rule.isPresent()) {
          throw new MacroException(String.format("no rule %s", target));
        }
        ProjectFilesystem filesystem = rule.get().getProjectFilesystem();
        Path tempFile = createTempFile(filesystem, target, input);
        filesystem.writeContentsToPath(replacer.replace(input), tempFile);
        return "@" + filesystem.resolve(tempFile);
      } catch (IOException e) {
        throw new MacroException("Unable to create file to hold expanded results", e);
      }
    };
  }

  /** @return The absolute path to the temp file. */
  private static Path createTempFile(
      ProjectFilesystem filesystem, BuildTarget target, MacroMatchResult matchResult)
      throws IOException {
    Path directory = getMacroPath(filesystem, target);
    filesystem.mkdirs(directory);

    // "prefix" should give a stable name, so that the same delegate with the same input can output
    // the same file. We won't optimise for this case, since it's actually unlikely to happen within
    // a single run, but using a random name would cause 'buck-out' to expand in an uncontrolled
    // manner.
    String prefix =
        Hashing.sha1()
            .newHasher()
            .putString(matchResult.getMacroType(), UTF_8)
            .putString(Joiner.on('\0').join(matchResult.getMacroInput()), UTF_8)
            .hash()
            .toString();

    return filesystem.createTempFile(directory, prefix, ".macro");
  }
}
