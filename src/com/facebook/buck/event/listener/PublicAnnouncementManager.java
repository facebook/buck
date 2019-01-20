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

import com.facebook.buck.core.exceptions.HumanReadableException;
import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.distributed.FrontendService;
import com.facebook.buck.distributed.thrift.Announcement;
import com.facebook.buck.distributed.thrift.AnnouncementRequest;
import com.facebook.buck.distributed.thrift.FrontendRequest;
import com.facebook.buck.distributed.thrift.FrontendRequestType;
import com.facebook.buck.distributed.thrift.FrontendResponse;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.slb.ClientSideSlb;
import com.facebook.buck.slb.LoadBalancedService;
import com.facebook.buck.slb.ThriftOverHttpServiceConfig;
import com.facebook.buck.util.network.RemoteLogBuckConfig;
import com.facebook.buck.util.timing.Clock;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.io.IOException;
import java.util.Optional;

public class PublicAnnouncementManager {

  private static final Logger LOG = Logger.get(PublicAnnouncementManager.class);

  @VisibleForTesting
  static final String HEADER_MSG =
      "**-------------------------------**\n"
          + "**- Sticky Public Announcements -**\n"
          + "**-------------------------------**";

  @VisibleForTesting static final String ANNOUNCEMENT_TEMPLATE = "\n** %s Remediation: %s";

  private Clock clock;
  private BuckEventBus eventBus;
  private AbstractConsoleEventBusListener consoleEventBusListener;
  private String repository;
  private ListeningExecutorService service;
  private RemoteLogBuckConfig logConfig;

  public PublicAnnouncementManager(
      Clock clock,
      BuckEventBus eventBus,
      AbstractConsoleEventBusListener consoleEventBusListener,
      String repository,
      RemoteLogBuckConfig logConfig,
      ListeningExecutorService service) {
    this.clock = clock;
    this.consoleEventBusListener = consoleEventBusListener;
    this.eventBus = eventBus;
    this.repository = repository;
    this.logConfig = logConfig;
    this.service = service;
  }

  public void getAndPostAnnouncements() {
    ListenableFuture<ImmutableList<Announcement>> message =
        service.submit(
            () -> {
              Optional<ClientSideSlb> slb =
                  logConfig.getFrontendConfig().tryCreatingClientSideSlb(clock, eventBus);

              if (slb.isPresent()) {
                try (FrontendService frontendService =
                    new FrontendService(
                        ThriftOverHttpServiceConfig.of(
                            new LoadBalancedService(
                                slb.get(), logConfig.createOkHttpClient(), eventBus)))) {
                  AnnouncementRequest announcementRequest = new AnnouncementRequest();
                  announcementRequest.setBuckVersion(getBuckVersion());
                  announcementRequest.setRepository(repository);
                  FrontendRequest request = new FrontendRequest();
                  request.setType(FrontendRequestType.ANNOUNCEMENT);
                  request.setAnnouncementRequest(announcementRequest);

                  FrontendResponse response = frontendService.makeRequest(request);
                  return ImmutableList.copyOf(response.announcementResponse.announcements);
                } catch (IOException e) {
                  throw new HumanReadableException("Failed to perform request", e);
                }
              } else {
                throw new HumanReadableException("Failed to establish connection to server.");
              }
            });

    Futures.addCallback(
        message,
        new FutureCallback<ImmutableList<Announcement>>() {

          @Override
          public void onSuccess(ImmutableList<Announcement> announcements) {
            LOG.info("Public announcements fetched successfully.");
            if (!announcements.isEmpty()) {
              String announcement = HEADER_MSG;
              for (Announcement entry : announcements) {
                announcement =
                    announcement.concat(
                        String.format(
                            ANNOUNCEMENT_TEMPLATE,
                            consoleEventBusListener.ansi.asErrorText(entry.getErrorMessage()),
                            consoleEventBusListener.ansi.asInformationText(
                                entry.getSolutionMessage())));
              }
              consoleEventBusListener.setPublicAnnouncements(eventBus, Optional.of(announcement));
            }
          }

          @Override
          public void onFailure(Throwable t) {
            LOG.warn("Failed to get public announcements. Reason: %s", t.getMessage());
          }
        },
        MoreExecutors.directExecutor());
  }

  private String getBuckVersion() {
    return System.getProperty("buck.git_commit", "unknown");
  }
}
