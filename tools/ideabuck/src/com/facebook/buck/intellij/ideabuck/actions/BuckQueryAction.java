/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.intellij.ideabuck.actions;

import com.facebook.buck.intellij.ideabuck.build.BuckBuildManager;
import com.facebook.buck.intellij.ideabuck.build.BuckCommand;
import com.facebook.buck.intellij.ideabuck.build.BuckCommandHandler;
import com.facebook.buck.intellij.ideabuck.build.BuckQueryCommandHandler;
import com.google.common.base.Function;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;

/** Run variations of the buck query command. */
public class BuckQueryAction {
  public static final String ACTION_TITLE = "Run buck query";
  private static final Cache<String, List<String>> buckTargetCache =
      CacheBuilder.newBuilder().maximumSize(1000).build();
  private static final Set<String> ongoingQuery = new HashSet<>();

  private BuckQueryAction() {}

  /**
   * @deprecated This method was never intended for general use and should only be used by the
   *     {@link com.facebook.buck.intellij.ideabuck.actions.choosetargets.ChooseTargetContributor}.
   */
  @Deprecated
  public static synchronized List<String> execute(
      final Project project,
      final String target,
      final Function<List<String>, Void> fillTextResults) {
    return customExecuteForTargetCompletion(project, target, fillTextResults);
  }

  /**
   * Returns a list of targets that are equivalent to the expansion of the given alias or target
   * pattern.
   *
   * <p>Results are returned in exactly one of two ways:
   *
   * <ul>
   *   <li>If the target has been recently queried and is still cached, a cached list of results is
   *       returned immediately by this method, and the given callback is never invoked.
   *   <li>If the target is not cached, an empty list is returned immediately, and the results are
   *       returned in the given callback.
   * </ul>
   *
   * and returns an empty list on the first call
   *
   * <p>Note: This method was never intended for general use and should only be used by the {@link
   * com.facebook.buck.intellij.ideabuck.actions.choosetargets.ChooseTargetContributor}.
   */
  public static synchronized List<String> customExecuteForTargetCompletion(
      final Project project,
      final String target,
      final Function<List<String>, Void> fillTextResults) {
    if (ongoingQuery.contains(target)) {
      return Collections.emptyList();
    }

    List<String> targetsInBuckFile = buckTargetCache.getIfPresent(target);
    if (targetsInBuckFile != null) {
      return targetsInBuckFile;
    }

    ApplicationManager.getApplication()
        .executeOnPooledThread(
            new Runnable() {
              @Override
              public void run() {
                ongoingQuery.add(target);
                BuckBuildManager buildManager = BuckBuildManager.getInstance(project);

                BuckCommandHandler handler =
                    new BuckQueryCommandHandler(
                        project,
                        BuckCommand.QUERY,
                        new Function<List<String>, Void>() {
                          @Nullable
                          @Override
                          public Void apply(@Nullable List<String> strings) {
                            ongoingQuery.remove(target);
                            buckTargetCache.put(target, strings);
                            fillTextResults.apply(strings);
                            return null;
                          }
                        });
                handler.command().addParameter(target);
                buildManager.runBuckCommand(handler, ACTION_TITLE);
              }
            });
    return Collections.emptyList();
  }
}
