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

package com.facebook.buck.intellij.plugin.build;

import com.facebook.buck.intellij.plugin.actions.choosetargets.ChooseTargetContributor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.execution.process.ProcessOutputTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * Created by theodordidii on 1/27/16.
 */
public class BuckQueryCommandHandler extends BuckCommandHandler {

    public BuckQueryCommandHandler(
            final Project project,
            final VirtualFile root,
            final BuckCommand command) {
        super(project, VfsUtil.virtualToIoFile(root), command);
    }

    @Override
    protected void notifyLines(Key outputType, Iterator<String> lines, StringBuilder lineBuilder) {
        super.notifyLines(outputType, lines, lineBuilder);
        if (outputType == ProcessOutputTypes.STDOUT) {
            List<String> targetList = new LinkedList<String>();
            while (lines.hasNext()) {
                String outputLine = lines.next();
                if (!outputLine.isEmpty()) {
                    JsonElement jsonElement = new JsonParser().parse(outputLine);
                    if (jsonElement.isJsonArray()) {
                        JsonArray targets = jsonElement.getAsJsonArray();

                        for (JsonElement target : targets) {
                            targetList.add(target.getAsString());
                        }
                    }
                }
            }
            List<String> commandParameters = this.command().getParametersList().getParameters();
            // last parameter is the target
            ChooseTargetContributor.addToOtherTargets(
                    project,
                    targetList,
                    commandParameters.get(commandParameters.size() - 1));
        }
    }

    @Override
    protected boolean beforeCommand() {
        return true;
    }

    @Override
    protected void afterCommand() {
    }
}
