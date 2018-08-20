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

package com.facebook.buck.core.rules.attr;

import com.facebook.buck.core.build.context.BuildContext;
import com.facebook.buck.core.cell.CellPathResolver;
import com.facebook.buck.core.rules.BuildRule;
import com.facebook.buck.core.sourcepath.SourcePath;
import com.facebook.buck.core.sourcepath.resolver.SourcePathResolver;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.function.Predicate;

/** Used to tag a rule that supports dependency-file input-based rule keys. */
public interface SupportsDependencyFileRuleKey extends BuildRule {

  boolean useDependencyFileRuleKeys();

  /**
   * Returns a predicate that can tell whether a given path is covered by dep-file or not. Note that
   * being covered by dep-file doesn't necessarily mean being present in the dep-file. A covered
   * path will only be present if actually used and that's the core idea of dep-file keys.
   *
   * <p>I.e. this predicate should return true only for source paths that *may* be returned from
   * {@link #getInputsAfterBuildingLocally(BuildContext, CellPathResolver)}. This information is
   * used by the rule key builder to infer that inputs *not* in this list should be included
   * unconditionally in the rule key. Inputs that *are* in this list should be included in the rule
   * key if and only if they are actually being used for the rule. I.e. if they are present in the
   * dep-file listed by {@link #getInputsAfterBuildingLocally(BuildContext, CellPathResolver)}.
   */
  Predicate<SourcePath> getCoveredByDepFilePredicate(SourcePathResolver pathResolver);

  /**
   * Returns a predicate that can tell whether the existence of a given path may be of interest to
   * compiler. If this predicate returns true, the path should be included in the rule key. Note
   * that we only care about the existence here. The actual content of the file is not relevant.
   *
   * <p>The main purpose of this predicate is to support scenarios where compiler decides whether to
   * use a file or not solely based on its path. Of course, if compiler decides to use the file, it
   * should list it in the dep-file in which case both the path and the content will be included in
   * the rule key. However, relying only on presence in the dep-file for such paths is not
   * sufficient. The problem occurs when a new such file just gets added, in which case it won't be
   * present in the dep-file produced in the previous build, and yet if we run a local build now the
   * compiler may decide to use it. For that reason rule key needs to reflect existence of all such
   * files and change when a such a file gets added or removed.
   */
  Predicate<SourcePath> getExistenceOfInterestPredicate(SourcePathResolver pathResolver);

  /**
   * Returns a list of source paths that were actually used for the rule. This list comes from the
   * dep-file produced by compiler.
   */
  ImmutableList<SourcePath> getInputsAfterBuildingLocally(
      BuildContext context, CellPathResolver cellPathResolver) throws IOException;
}
