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

package com.facebook.buck.external.utils;

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.external.model.ExternalAction;
import com.facebook.buck.external.model.ParsedArgs;
import com.facebook.buck.step.isolatedsteps.IsolatedStep;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

/**
 * Utility class for getting the {@link IsolatedStep} instances associated with an {@link
 * ExternalAction}.
 */
public class BuildStepsRetriever {

  private BuildStepsRetriever() {}

  /**
   * Returns the {@link IsolatedStep} instances associated with the {@link ExternalAction} from the
   * {@link ParsedArgs}.
   */
  public static ImmutableList<IsolatedStep> getStepsForBuildable(ParsedArgs parsedArgs) {
    Class<? extends ExternalAction> externalActionClass = parsedArgs.getExternalActionClass();
    try {
      Constructor<? extends ExternalAction> constructor =
          externalActionClass.getDeclaredConstructor();
      constructor.setAccessible(true);
      ExternalAction externalAction = constructor.newInstance();
      return externalAction.getSteps(parsedArgs.getBuildableCommand());
    } catch (NoSuchMethodException e) {
      throw new IllegalStateException(
          String.format(
              "External action %s must have empty constructor", externalActionClass.getName()));
    } catch (ClassCastException e) {
      throw new IllegalStateException(
          String.format("%s does not implement ExternalAction", externalActionClass.getName()));
    } catch (InvocationTargetException | IllegalAccessException | InstantiationException e) {
      throw new HumanReadableException(
          e, "Failed to instantiate external action %s", externalActionClass);
    } catch (Exception e) {
      throw new IllegalStateException(
          String.format(
              "Failed to get steps from external action %s", externalActionClass.getName()),
          e);
    }
  }
}
