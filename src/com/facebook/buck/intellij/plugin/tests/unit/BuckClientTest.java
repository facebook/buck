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

package unit;

import com.facebook.buck.intellij.plugin.ws.BuckClient;
import com.facebook.buck.intellij.plugin.ws.BuckSocket;
import com.facebook.buck.intellij.plugin.ws.buckevents.BuckEventsHandlerInterface;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BuckClientTest {

    public class TestBuckEventHandler implements BuckEventsHandlerInterface {

        private String lastMessage = "";
        public String getLastMessage() {
            return this.lastMessage;
        }

        @Override
        public void onConnect() {
        }

        @Override
        public void onDisconnect() {
        }

        @Override
        public void onMessage(String message) {
            this.lastMessage = message;
        }
    }

    @Test
    public void testConnectDisconnect() {
        TestBuckEventHandler handler = new TestBuckEventHandler();
        BuckClient client = new BuckClient(1234, handler);

        // Set the socket we control
        BuckSocket socket = new BuckSocket(handler);

        client.setSocket(socket);
        client.connect();

        assertTrue(client.isConnected());
        client.disconnect();
        assertFalse(client.isConnected());
    }

    @Test
    public void testMessages() {
        TestBuckEventHandler handler = new TestBuckEventHandler();
        BuckClient client = new BuckClient(1234, handler);

        // Set the socket we control
        BuckSocket socket = new BuckSocket(handler);

        client.setSocket(socket);

        client.connect();

        assertEquals("", handler.getLastMessage());

        socket.onMessage("some text");
        assertEquals("some text", handler.getLastMessage());

        socket.onMessage("some text 1");
        socket.onMessage("some text 2");
        socket.onMessage("some text 3");
        socket.onMessage("some text 4");
        assertEquals("some text 4", handler.getLastMessage());
    }
}
