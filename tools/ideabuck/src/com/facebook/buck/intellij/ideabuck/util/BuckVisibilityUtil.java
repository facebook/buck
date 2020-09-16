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

package com.facebook.buck.intellij.ideabuck.util;

import com.facebook.buck.intellij.ideabuck.api.BuckTarget;
import com.facebook.buck.intellij.ideabuck.api.BuckTargetPattern;
import java.util.List;
import javax.annotation.Nullable;

/** Methods for finding PsiElements, Buck files and other data from an AnActionEvent */
public class BuckVisibilityUtil {

  private BuckVisibilityUtil() {}

  public static boolean isVisibleTo(
      @Nullable BuckTarget target, @Nullable List<BuckTargetPattern> visibilities) {
    if (visibilities == null) {
      return true;
    }
    return visibilities.stream().anyMatch(pattern -> pattern.matches(target));
  }
}
