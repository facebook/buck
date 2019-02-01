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

package com.facebook.buck.event.listener;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.core.config.BuckConfig;
import com.facebook.buck.core.config.FakeBuckConfig;
import com.facebook.buck.core.model.BuildId;
import com.facebook.buck.distributed.thrift.Announcement;
import com.facebook.buck.distributed.thrift.AnnouncementResponse;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.BuckEventBusForTests;
import com.facebook.buck.slb.ThriftProtocol;
import com.facebook.buck.slb.ThriftUtil;
import com.facebook.buck.test.config.TestResultSummaryVerbosity;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.testutil.integration.HttpdForTests;
import com.facebook.buck.util.environment.DefaultExecutionEnvironment;
import com.facebook.buck.util.environment.EnvVariablesProvider;
import com.facebook.buck.util.environment.ExecutionEnvironment;
import com.facebook.buck.util.network.RemoteLogBuckConfig;
import com.facebook.buck.util.timing.Clock;
import com.facebook.buck.util.timing.DefaultClock;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.Test;

public class PublicAnnouncementManagerIntegrationTest {

  private static final String REPOSITORY = "repository-name";
  private static final String ERROR_MSG = "This is the error message.";
  private static final String SOLUTION_MSG = "This is the solution message.";

  private Path logPath;

  @Before
  public void createTestLogFile() {
    logPath = Jimfs.newFileSystem(Configuration.unix()).getPath("log.txt");
  }

  @Test
  public void testAnnouncementsWork() throws Exception {
    AtomicReference<byte[]> requestBody = new AtomicReference<>();

    try (HttpdForTests httpd = new HttpdForTests()) {
      httpd.addHandler(
          new AbstractHandler() {
            @Override
            public void handle(
                String s,
                Request request,
                HttpServletRequest httpServletRequest,
                HttpServletResponse httpServletResponse)
                throws IOException {
              httpServletResponse.setStatus(200);
              request.setHandled(true);
              if (request.getHttpURI().getPath().equals("/status.php")) {
                return;
              }

              requestBody.set(ByteStreams.toByteArray(httpServletRequest.getInputStream()));
              FrontendRequest thriftRequest = new FrontendRequest();
              ThriftUtil.deserialize(ThriftProtocol.BINARY, requestBody.get(), thriftRequest);
              assertTrue(
                  "Request should contain the repository.",
                  thriftRequest.getAnnouncementRequest().getRepository().equals(REPOSITORY));

              try (DataOutputStream out =
                  new DataOutputStream(httpServletResponse.getOutputStream())) {
                Announcement announcement = new Announcement();
                announcement.setErrorMessage(ERROR_MSG);
                announcement.setSolutionMessage(SOLUTION_MSG);
                AnnouncementResponse announcementResponse = new AnnouncementResponse();
                announcementResponse.setAnnouncements(ImmutableList.of(announcement));
                FrontendResponse frontendResponse = new FrontendResponse();
                frontendResponse.setType(FrontendRequestType.ANNOUNCEMENT);
                frontendResponse.setAnnouncementResponse(announcementResponse);

                out.write(ThriftUtil.serialize(ThriftProtocol.BINARY, frontendResponse));
              }
            }
          });
      httpd.start();

      Clock clock = new DefaultClock();
      BuckEventBus eventBus = BuckEventBusForTests.newInstance(clock);
      ExecutionEnvironment executionEnvironment =
          new DefaultExecutionEnvironment(
              EnvVariablesProvider.getSystemEnv(), System.getProperties());
      BuckConfig buckConfig =
          new FakeBuckConfig.Builder()
              .setSections(
                  ImmutableMap.of(
                      "log",
                      ImmutableMap.of(
                          "slb_server_pool", "http://localhost:" + httpd.getRootUri().getPort())))
              .build();

      TestConsole console = new TestConsole();
      SuperConsoleEventBusListener listener =
          new SuperConsoleEventBusListener(
              new SuperConsoleConfig(FakeBuckConfig.builder().build()),
              new RenderingConsole(clock, console),
              clock,
              /* verbosity */ TestResultSummaryVerbosity.of(false, false),
              executionEnvironment,
              Locale.US,
              logPath,
              TimeZone.getTimeZone("UTC"),
              new BuildId("1234-5679"),
              false,
              Optional.empty(),
              ImmutableList.of());
      eventBus.register(listener);

      PublicAnnouncementManager manager =
          new PublicAnnouncementManager(
              clock,
              eventBus,
              listener,
              REPOSITORY,
              new RemoteLogBuckConfig(buckConfig),
              MoreExecutors.newDirectExecutorService());

      manager.getAndPostAnnouncements();

      Optional<String> announcements = listener.getPublicAnnouncements();
      assertEquals(
          "The header and the message",
          announcements.get(),
          "**-------------------------------**\n"
              + "**- Sticky Public Announcements -**\n"
              + "**-------------------------------**\n"
              + "** This is the error message. Remediation: This is the solution message.");
    }
  }
}
