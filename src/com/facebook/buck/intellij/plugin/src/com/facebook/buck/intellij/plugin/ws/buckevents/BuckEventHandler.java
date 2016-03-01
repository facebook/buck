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

public class BuckEventHandler implements BuckEventHandlerInterface {

    private Runnable mOnConnectHandler = null;
    private Runnable mOnDisconnectHandler = null;

    public BuckEventHandler(Runnable onConnectHandler,
                            Runnable onDisconnectHandler) {

        mOnConnectHandler = onConnectHandler;
        mOnDisconnectHandler = onDisconnectHandler;
    }

    @Override
    public void onConnect() {
        if (mOnConnectHandler != null) {
            mOnConnectHandler.run();
        }
    }

    @Override
    public void onDisconnect() {
        if (mOnDisconnectHandler != null) {
            mOnDisconnectHandler.run();
        }
    }

    @Override
    public void onMessage(final String message) {
    }
}
