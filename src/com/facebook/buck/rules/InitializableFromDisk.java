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

/**
 * Object that has in-memory data structures that need to be populated as a result of executing its
 * steps. An object that implements this interface will <em>always</em> have its internal data
 * structures initialized through this interface, regardless of whether it was built locally or read
 * from cache. This ensures that a rule is always in the same state once {@code isRuleBuilt()}
 * returns {@code true}.
 * <p>
 * Objects that implement this interface should create getter methods that delegate to
 * {@link #getBuildOutput()} to access the in-memory data structures rather than have clients invoke
 * {@link #getBuildOutput()} directly. This ensures that all getters go through any protections
 * provided by {@link #getBuildOutput()}. (If this were an abstract class, {@link #getBuildOutput()}
 * would be protected.)
 * <p>
 * This implementation is heavy-handed to ensure that certain invariants are maintained when
 * creating, setting, and getting the build output. Specifically, implementations of this interface
 * should look like the following:
 * <pre>
 * &#64;Nullable
 * private T buildOutput;
 *
 * &#64;Override
 * public T initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo) {
 *   // ...add your magic here...
 *   return t;
 * }
 *
 * &#64;Override
 * public void setBuildOutput(T buildOutput) {
 *   Preconditions.checkState(this.buildOutput == null,
 *       "buildOutput should not already be set for %s.",
 *       this);
 *   this.buildOutput = buildOutput;
 * }
 *
 * &#64;Override
 * public T getBuildOutput() {
 *   Preconditions.checkState(buildOutput == null, "buildOutput must already be set for %s.", this);
 *   return buildOutput;
 * }
 * </pre>
 */
public interface InitializableFromDisk<T> {

  /**
   * @param onDiskBuildInfo can be used to read metadata from disk to help initialize the rule.
   * @return an object that has the in-memory data structures that need to be populated as a result
   *     of executing this object's steps.
   */
  public T initializeFromDisk(OnDiskBuildInfo onDiskBuildInfo);

  /**
   * This should be invoked only by the build engine (currently, {@link AbstractCachingBuildRule})
   * that invoked {@link #initializeFromDisk(OnDiskBuildInfo)}.
   * <p>
   * @throws IllegalStateException if this method has already been invoked.
   */
  public void setBuildOutput(T buildOutput) throws IllegalStateException;

  /**
   * @return the value passed to {@link #setBuildOutput(Object)}.
   * @throws IllegalStateException if {@link #setBuildOutput(Object)} has not been invoked yet.
   */
  public T getBuildOutput() throws IllegalStateException;
}
