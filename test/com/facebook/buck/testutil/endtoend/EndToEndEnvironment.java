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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link EndToEndEnvironment} allows the EndToEndTestRunner to create test situations for many
 * possible environmental conditions.
 *
 * <p>Giving at least 1 templateSet and 1 commandSet with the "with" methods is required for any
 * tests to be run.
 *
 * <p>Buckd is automatically toggled as off, and variableMaps automatically doesn't override
 * anything.
 */
public class EndToEndEnvironment {

  private List<String[]> templates = new ArrayList<>();
  private String[] command = new String[] {};
  private ToggleState buckdToggled = ToggleState.OFF;
  private List<Map<String, String>> variableMaps = new ArrayList<>();

  public EndToEndEnvironment() {}

  /**
   * Gets templates, which are pre-made directories complete with sample buck projects.
   *
   * <p>The templates can be found in test/com/facebook/buck/testutil/endtoend/testdata
   */
  public List<String[]> getTemplates() {
    return templates;
  }

  /**
   * Gets commands, which are is a set of command + args to send to buck.
   *
   * <p>As an example, giving [["build" "project"], ["build", "project2"]] would run a test after
   * buck building project and another test after buck build project2
   */
  public String[] getCommand() {
    return command;
  }

  /** Gets whether tests should be run with buckd off (default), on, or both (two separate tests) */
  public ToggleState getBuckdToggled() {
    return buckdToggled;
  }

  /**
   * Gets the list of environment variable override maps, where a test will be run with each
   * individual map.
   *
   * <p>If no environment override maps have been provided, then this will return a list with an
   * empty override map inside.
   */
  public List<Map<String, String>> getVariableMaps() {
    if (!variableMaps.isEmpty()) {
      return variableMaps;
    }
    List<Map<String, String>> emptyMapList = new ArrayList<>();
    emptyMapList.add(Collections.emptyMap());
    return emptyMapList;
  }

  /**
   * Adds a new set of pre-made templates to the test configurations
   *
   * <p>The templates can be found in test/com/facebook/buck/testutil/endtoend/testdata
   */
  public EndToEndEnvironment addTemplates(String... templateSet) {
    this.templates.add(templateSet);
    return this;
  }

  /**
   * Sets command, which are sets of command + args to send to buck.
   *
   * <p>As an example, giving [["build" "project"], ["build", "project2"]] would run a test after
   * buck building project and another test after buck build project2
   */
  public EndToEndEnvironment withCommand(String... command) {
    this.command = command;
    return this;
  }

  /** Sets whether tests should be run with buckd off (default), on, or both (two separate tests) */
  public EndToEndEnvironment withBuckdToggled(ToggleState buckdToggled) {
    this.buckdToggled = buckdToggled;
    return this;
  }

  /**
   * Add an environment variable override map to run tests on.
   *
   * <p>Note: If any map is given, then the "empty" override map will need to be re-added if you
   * want to include it.
   */
  public EndToEndEnvironment addVariableMap(Map<String, String> environment) {
    variableMaps.add(environment);
    return this;
  }
}
