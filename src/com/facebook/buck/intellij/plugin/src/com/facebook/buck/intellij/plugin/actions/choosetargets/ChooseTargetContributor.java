/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.intellij.plugin.actions.choosetargets;

import com.facebook.buck.intellij.plugin.build.BuckBuildTargetAliasParser;
import com.google.common.base.Joiner;
import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChooseTargetContributor implements ChooseByNameContributor {

  public static final String ALIAS_SEPARATOR = "::";

  @Override
  public String[] getNames(Project project, boolean includeNonProjectItems) {
    BuckBuildTargetAliasParser.parseAlias(project.getBasePath());
    List<String> names = new ArrayList<String>();

    for (Map.Entry<String, Set<String>> entry :
        BuckBuildTargetAliasParser.sTargetAlias.entrySet()) {
      String target = entry.getKey();
      Set<String> alias = entry.getValue();
      target += ALIAS_SEPARATOR + Joiner.on(',').join(alias);
      names.add(target);
    }

    return names.toArray(new String[names.size()]);
  }

  @Override
  public NavigationItem[] getItemsByName(
      String name,
      String pattern,
      Project project,
      boolean includeNonProjectItems) {
    String alias = null;
    int index = name.lastIndexOf(ALIAS_SEPARATOR);
    if (index > 0) {
      alias = name.substring(index + ALIAS_SEPARATOR.length());
      name = name.substring(0, index);
    }
    return new NavigationItem[] { new ChooseTargetItem(name, alias) };
  }
}
