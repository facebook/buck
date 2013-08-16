/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;

import java.io.IOException;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Represents something that Buck is able to build, encapsulating the logic of how to determine
 * whether the rule needs to be rebuilt and how to actually go about building the rule itself.
 */
public interface Buildable {

  public BuildableProperties getProperties();

  /**
   * Get the set of input files whose contents should be hashed for the purpose of determining
   * whether this rule is cached.
   * <p>
   * Note that inputs are iterable and should generally be alphatized so that lists with the same
   * elements will be {@code .equals()} to one another. However, for some build rules (such as
   * {@link com.facebook.buck.shell.Genrule}), the order of the inputs is significant and in these
   * cases the inputs may be ordered in any way the rule feels most appropriate.
   */
  public Iterable<String> getInputsToCompareToOutput();

  /**
   * When this method is invoked, all of its dependencies will have been built.
   */
  public List<Step> getBuildSteps(BuildContext context) throws IOException;

  public RuleKey.Builder appendToRuleKey(RuleKey.Builder builder) throws IOException;

  /**
   * This method is invoked if the output file is successfully fetched from the
   * {@link ArtifactCache}. This is where a rule has the opportunity to record any relevant metadata
   * about the artifact.
   * <p>
   * The default implementation of this method is empty.
   * @param cache The {@link ArtifactCache} that was used to fetch the output file.
   * @param projectFilesystem to use to determine where a metadata file should be written.
   */
  public void recordOutputFileDetailsAfterFetchFromArtifactCache(ArtifactCache cache,
      ProjectFilesystem projectFilesystem) throws IOException;

  /**
   * @return the relative path to the primary output of the build rule. If non-null, this path must
   *     identify a single file (as opposed to a directory).
   */
  @Nullable
  public String getPathToOutputFile();
}
