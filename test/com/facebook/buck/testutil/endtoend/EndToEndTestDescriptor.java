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

package com.facebook.buck.testutil.endtoend;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.UserFlavor;
import com.facebook.buck.testutil.PlatformUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.runners.model.FrameworkMethod;

/** {@link EndToEndTestDescriptor} is a data class to serve information about a test */
public class EndToEndTestDescriptor {
  private final FrameworkMethod method;
  private final String[] templateSet;
  private final String command;
  private final String[] buildTargets;
  private final String[] arguments;
  private final Boolean buckdEnabled;
  private final Map<String, String> variableMap;
  private String name;
  private boolean nameIsCached = false;
  private PlatformUtils platformUtils = PlatformUtils.getForPlatform();

  public static EndToEndTestDescriptor failedSetup(String testClassName) {
    EndToEndTestDescriptor failedDescriptor =
        new EndToEndTestDescriptor(null, null, null, null, null, false, null);
    failedDescriptor.name = testClassName + "SetupFailed";
    failedDescriptor.nameIsCached = true;
    return failedDescriptor;
  }

  public EndToEndTestDescriptor(
      FrameworkMethod method,
      String[] templateSet,
      String command,
      String[] buildTargets,
      String[] arguments,
      Boolean buckdEnabled,
      Map<String, String> variableMap) {
    this.method = method;
    this.templateSet = templateSet;
    this.command = command;
    this.buildTargets = buildTargets;
    this.arguments = arguments;
    this.buckdEnabled = buckdEnabled;
    this.variableMap = variableMap;
  }

  private String capitalizeAndJoin(String... input) {
    StringBuilder stringBuilder = new StringBuilder();
    for (String s : input) {
      if (s.length() > 0) {
        stringBuilder.append(s.substring(0, 1).toUpperCase() + s.substring(1));
      }
    }
    return stringBuilder.toString();
  }

  /**
   * Builds a (with the exception of identical tests with different variable maps) unique name for
   * the test.
   */
  public String getName() {
    if (nameIsCached) {
      return name;
    }
    StringBuilder stringBuilder = new StringBuilder("Test");
    stringBuilder.append(capitalizeAndJoin(templateSet));
    stringBuilder.append(capitalizeAndJoin(command));
    stringBuilder.append(capitalizeAndJoin(arguments));
    stringBuilder.append(buckdEnabled ? "BuckdOn" : "BuckdOff");
    // TODO: Should handle only variable map being different, but not too verbose
    stringBuilder.append(method.getName());
    name = stringBuilder.toString();
    nameIsCached = true;
    return name;
  }

  /**
   * Gets a list of templateSets, where each templateSet is an array of strings representing the
   * names of pre-made templates to include in the testing environment (which can be found in
   * test/com/facebook/buck/testutil/endtoend/testdata)
   */
  public String[] getTemplateSet() {
    return templateSet;
  }

  /** Gets the command to run against the test environment */
  public String[] getFullCommand() {
    List<String> fullCommandList = new ArrayList<>();
    fullCommandList.add(command);
    fullCommandList.addAll(
        Arrays.stream(buildTargets)
            .map(
                (String target) -> {
                  BuildTarget buildTarget = BuildTargetFactory.newInstance(target);
                  Optional<String> platformFlavorName = platformUtils.getFlavor();
                  if (!platformFlavorName.isPresent()) {
                    return buildTarget.getFullyQualifiedName();
                  }
                  Flavor platformFlavor =
                      UserFlavor.of(platformFlavorName.get(), platformFlavorName.get());
                  return buildTarget.withFlavors(platformFlavor).getFullyQualifiedName();
                })
            .collect(Collectors.toList()));
    fullCommandList.addAll(Arrays.stream(arguments).collect(Collectors.toList()));

    String[] fullCommand = new String[fullCommandList.size()];
    return fullCommandList.toArray(fullCommand);
  }

  /** Gets whether buckd should be enabled during the test or not */
  public Boolean getBuckdEnabled() {
    return buckdEnabled;
  }

  /** Gets an environment variable override map to be used during the test */
  public Map<String, String> getVariableMap() {
    return variableMap;
  }

  /**
   * Returns the verification test method that is defined in the testClass using the {@link
   * org.junit.Test}
   */
  public FrameworkMethod getMethod() {
    return method;
  }
}
