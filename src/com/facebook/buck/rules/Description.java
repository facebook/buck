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
 * The Source of Truth about a {@link BuildRule}, providing mechanisms to expose the arguments that
 * rules derived from the Buildable take and providing a factory for those Buildables. It is
 * expected that instances of this class are stateless.
 *
 * @param <T> The object describing the parameters to be passed to the {@link BuildRule}. How this
 *     is processed is described in the class level javadoc of {@link ConstructorArgMarshaller}.
 *
 */
public interface Description<T extends ConstructorArg> {

  /**
   * @return The {@link BuildRuleType} being described.
   */
  BuildRuleType getBuildRuleType();

  /**
   * @return An instance of the argument that must later be passed to createBuildRule().
   * @see ConstructorArgMarshaller
   *
   */
  T createUnpopulatedConstructorArg();

  /**
   * @param resolver For querying for build rules by their targets.
   * @param args A constructor argument, as returned by {@link #createUnpopulatedConstructorArg()}.
   * @return The {@link BuildRule} that describes the default flavour of the rule being described.
   */
  <A extends T> BuildRule createBuildRule(
      BuildRuleParams params,
      BuildRuleResolver resolver,
      A args);
}
