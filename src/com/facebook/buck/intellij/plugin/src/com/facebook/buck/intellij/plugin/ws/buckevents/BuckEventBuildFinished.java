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

package com.facebook.buck.intellij.plugin.ws.buckevents;

import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckBuildEndConsumer;
import com.facebook.buck.intellij.plugin.ws.buckevents.consumers.BuckEventsConsumerFactory;

public class BuckEventBuildFinished extends BuckEventBase {
    public static final String EVENT_TYPE = "BuildFinished";

    @Override
    public void handleEvent(BuckEventsConsumerFactory factory) {
        BuckBuildEndConsumer publisher = factory.getBuildEndConsumer();
        publisher.consumeBuildEnd(buildId, timestamp);
    }

    @Override
    public String getEventType() {
        return EVENT_TYPE;
    }

    @Override
    public int getPriority() {
        return PRIORITY_HIGH;
    }
}
