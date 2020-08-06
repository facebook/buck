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

package com.facebook.buck.httpserver;

import com.facebook.buck.event.external.events.BuckEventExternalInterface;
import com.facebook.buck.event.external.events.CompilerErrorEventExternalInterface;
import com.facebook.buck.event.external.events.ConsoleEventExternalInterface;
import com.facebook.buck.event.external.events.IndividualTestEventFinishedExternalInterface;
import com.facebook.buck.event.external.events.InstallFinishedEventExternalInterface;
import com.facebook.buck.event.external.events.ProgressEventInterface;
import com.facebook.buck.event.external.events.TestRunFinishedEventInterface;
import com.facebook.buck.util.json.ObjectMappers;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import javax.annotation.Nonnull;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;

public class StreamingWebSocketServlet extends WebSocketServlet {

  /** Backed by a ConcurrentMap, so this is threadsafe. */
  private final Set<MyWebSocket> connections = Collections.newSetFromMap(Maps.newConcurrentMap());

  private final ConcurrentMap<String, BuckEventExternalInterface> storedEventsForReplay =
      Maps.newConcurrentMap();

  @Override
  public void configure(WebSocketServletFactory factory) {
    // Most implementations of this method simply invoke factory.register(DispatchSocket.class);
    // however, that requires DispatchSocket to have a no-arg constructor. That does not work for
    // us because we would like all WebSockets created by this factory to have a reference to this
    // parent class. This is why we override the default WebSocketCreator for the factory.
    WebSocketCreator wrapperCreator = (req, resp) -> new MyWebSocket();
    factory.setCreator(wrapperCreator);
  }

  /** Sends the message to all WebSockets that are subscribed to the given event. */
  public void tellClients(BuckEventExternalInterface event) {
    String eventName = event.getEventName();
    if (event.storeLastInstanceAndReplayForNewClients()) {
      storedEventsForReplay.put(eventName, event);
    }
    // We don't want to pay the cost of serializing to JSON unless
    // at least one client is connected and subscribed.
    String message = null;
    for (MyWebSocket webSocket : connections) {
      if (webSocket.isConnected() && webSocket.isSubscribedTo(eventName)) {
        if (message == null) {
          try {
            message = ObjectMappers.WRITER.writeValueAsString(event);
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
        webSocket.getRemote().sendStringByFuture(message);
      }
    }
  }

  /**
   * @return Number of clients streaming from webserver. This may be called on any thread since
   *     connections is a thread-safe set, but beware of the usual caveat: by the time you act on
   *     the information returned, the number of active connections may have changed!
   */
  public int getNumActiveConnections() {
    return connections.size();
  }

  /** This is the httpserver component of a WebSocket that maintains a session with one client. */
  public class MyWebSocket extends WebSocketAdapter {
    private @Nonnull ImmutableSet<String> subscribedEvents = ImmutableSet.of();

    @Override
    public void onWebSocketConnect(Session session) {
      super.onWebSocketConnect(session);
      subscribedEvents = parseSubscribedEvents(session);
      connections.add(this);

      for (BuckEventExternalInterface event : storedEventsForReplay.values()) {
        if (subscribedEvents.contains(event.getEventName())) {
          try {
            getRemote().sendStringByFuture(ObjectMappers.WRITER.writeValueAsString(event));
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason) {
      super.onWebSocketClose(statusCode, reason);
      connections.remove(this);
    }

    @Override
    public void onWebSocketText(String message) {
      super.onWebSocketText(message);
      // TODO(mbolin): Handle requests from client instead of only pushing data down.
    }

    /** @return true if client is subscribed to given event */
    public boolean isSubscribedTo(String eventName) {
      return subscribedEvents.contains(eventName);
    }
  }

  private static @Nonnull ImmutableSet<String> parseSubscribedEvents(Session session) {
    Map<String, List<String>> params = session.getUpgradeRequest().getParameterMap();
    List<String> events = params.get("event");
    if (events == null || events.isEmpty()) {
      // If the client doesn't specify a list of events, subscribe them to a default set.
      // This default set is merely historical and has no rhyme or reason to it.
      // A good breaking change might be to *require* passing a non-empty list of events.
      return ImmutableSet.of(
          BuckEventExternalInterface.PARSE_STARTED,
          BuckEventExternalInterface.PARSE_FINISHED,
          BuckEventExternalInterface.BUILD_STARTED,
          BuckEventExternalInterface.CACHE_RATE_STATS_UPDATE_EVENT,
          BuckEventExternalInterface.BUILD_FINISHED,
          BuckEventExternalInterface.TEST_RUN_STARTED,
          TestRunFinishedEventInterface.RUN_COMPLETE,
          BuckEventExternalInterface.INDIVIDUAL_TEST_AWAITING_RESULTS,
          IndividualTestEventFinishedExternalInterface.RESULTS_AVAILABLE,
          InstallFinishedEventExternalInterface.INSTALL_FINISHED,
          CompilerErrorEventExternalInterface.COMPILER_ERROR_EVENT,
          ConsoleEventExternalInterface.CONSOLE_EVENT,
          ProgressEventInterface.BUILD_PROGRESS_UPDATED,
          ProgressEventInterface.PARSING_PROGRESS_UPDATED,
          ProgressEventInterface.PROJECT_GENERATION_PROGRESS_UPDATED,
          BuckEventExternalInterface.PROJECT_GENERATION_STARTED,
          BuckEventExternalInterface.PROJECT_GENERATION_FINISHED);
    }

    // Filter out empty strings and split comma separated parameters.
    // HTTP allows you to specify parameters more than once, so you can do either:
    //   ?event=Foo&event=Bar&event=Baz
    // or
    //   ?event=Foo,Bar,Baz
    // ... or even both
    ImmutableSet.Builder<String> subscribed = ImmutableSet.<String>builder();
    events.forEach(
        e -> subscribed.addAll(Splitter.on(',').trimResults().omitEmptyStrings().splitToList(e)));
    return subscribed.build();
  }
}
