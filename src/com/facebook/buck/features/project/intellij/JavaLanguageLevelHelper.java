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

package com.facebook.buck.features.project.intellij;

import com.facebook.buck.core.model.targetgraph.TargetNode;
import com.facebook.buck.features.project.intellij.model.IjProjectConfig;
import com.facebook.buck.jvm.java.JavacLanguageLevelOptions;
import com.facebook.buck.jvm.java.JvmLibraryArg;
import com.facebook.infer.annotation.PropagatesNullable;
import java.util.Optional;

public final class JavaLanguageLevelHelper {

  private JavaLanguageLevelHelper() {}

  /** Get Java language level for a JVM library target */
  public static <T extends JvmLibraryArg> Optional<String> getLanguageLevel(
      IjProjectConfig projectConfig, TargetNode<T> targetNode) {

    JvmLibraryArg arg = targetNode.getConstructorArg();

    if (arg.getSource().isPresent()) {
      JavacLanguageLevelOptions languageLevelOptions =
          projectConfig.getJavaBuckConfig().getJavacLanguageLevelOptions();
      String defaultSourceLevel = languageLevelOptions.getSourceLevel();
      String defaultTargetLevel = languageLevelOptions.getTargetLevel();
      boolean languageLevelsAreDifferent =
          !defaultSourceLevel.equals(arg.getSource().orElse(defaultSourceLevel))
              || !defaultTargetLevel.equals(arg.getTarget().orElse(defaultTargetLevel));
      if (languageLevelsAreDifferent) {
        return Optional.of(normalizeSourceLevel(arg.getSource().get()));
      }
    }

    return Optional.empty();
  }

  /** Ensures that source level has format "majorVersion.minorVersion". */
  public static String normalizeSourceLevel(String jdkVersion) {
    if (jdkVersion.length() == 1) {
      return "1." + jdkVersion;
    } else {
      return jdkVersion;
    }
  }

  public static String convertLanguageLevelToIjFormat(@PropagatesNullable String languageLevel) {
    if (languageLevel == null) {
      return null;
    }

    return "JDK_" + normalizeSourceLevel(languageLevel).replace('.', '_');
  }
}
