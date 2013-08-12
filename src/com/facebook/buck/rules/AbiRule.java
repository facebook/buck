/*
 * Copyright 2012-present Facebook, Inc.
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

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;

import java.io.IOException;

/**
 * {@link BuildRule} that can avoid rebuilding itself when the ABI of its deps has not changed and
 * all properties of the rule other than its deps have not changed.
 */
public interface AbiRule {

  /**
   * Normally, a {@link RuleKey} is a function of the {@link RuleKey} of each of its deps as well as
   * that of its inputs. This returns a {@link RuleKey} that is a function of only its inputs, which
   * can be used to determine whether the definition or inputs of the rule changed independent of
   * changes to its [transitive] deps.
   *
   * @return {@link Optional#absent()} if there is an error when computing the {@link RuleKey}
   */
  public Optional<RuleKey> getRuleKeyWithoutDeps() throws IOException;

  /**
   * This is the same as {@link #getRuleKeyWithoutDeps()}, but is the {@link RuleKey} for the output
   * that is currently written to disk, rather than the output that will be written once this
   * {@link AbiRule} is built. This is designed to be compared with the result of
   * {@link #getRuleKeyWithoutDeps()} as a heuristic for whether this rule needs to be rebuilt if
   * its deps [or the ABI of its deps] have not changed.
   *
   * @return {@link Optional#absent()} if this {@link BuildRule} has not been built locally before,
   *     in which case there is no {@link RuleKey} from a previous run.
   */
  public Optional<RuleKey> getRuleKeyWithoutDepsOnDisk(ProjectFilesystem projectFilesystem);

  /**
   * Returns a {@link Sha1HashCode} that represents the ABI of this rule's deps.
   *
   * @return {@link Optional#absent()} if not all deps that should have an ABI key have one.
   */
  public Optional<Sha1HashCode> getAbiKeyForDeps() throws IOException;

  /**
   * This is the same as {@link #getAbiKeyForDeps()}, but is the ABI key of this rule's deps that
   * was computed the last time this rule was built locally, so its value is read from disk. This is
   * designed to be compared with the result of {@link #getAbiKeyForDeps()} as a heuristic for
   * whether this rule needs to be rebuilt.
   *
   * @return {@link Optional#absent()} if the deps of this {@link BuildRule} have not been built
   *     locally before, in which case there is no data from a previous run.
   */
  public Optional<Sha1HashCode> getAbiKeyForDepsOnDisk(ProjectFilesystem projectFilesystem);

  /**
   * Instructs this rule to report the ABI it has on disk as its current ABI.
   *
   * @return whether the ABI was read from disk and loaded into memory successfully.
   */
  public boolean initializeFromDisk(ProjectFilesystem projectFilesystem);
}
