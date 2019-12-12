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

package com.facebook.buck.support.jvm;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.event.BuckEventBus;
import com.google.common.collect.ImmutableSet;
import com.sun.management.GarbageCollectionNotificationInfo;
import com.sun.management.GcInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * JVM {@link NotificationListener} tuned to listen for GC events and emit GC events on the Buck
 * event bus. This class receives GC events directly from the JVM and reports them in as high
 * fidelity as possible to various log systems, where they can be used to correlate JVM GCs with
 * other events in the Buck system.
 *
 * <p>This class is expected to work the same with any GC, but Buck currently uses the G1 GC, so its
 * heuristics are tuned to that.
 */
public final class GCNotificationEventEmitter implements NotificationListener {
  private static Logger LOG = Logger.get(GCNotificationEventEmitter.class);

  /**
   * The set of collectors known to operate on the young generation. The JVM allows mixing and
   * matching of GC implementations even at the generational level. These are the four that you can
   * get:
   *
   * <ul>
   *   <li>G1 Young Generation, when using G1 <code>-XX:+UseG1GC</code>
   *   <li>ParNew, when using the parallel copy collector <code>-XX:+UseParNewGC</code>
   *   <li>PS Scavenge, when using the parallel scavenging collector <code>XX:+UseParallelGC</code>
   *       /li>
   *   <li>Copy, when using the serial copy collector <code>-XX:+UseSerialGC</code>
   * </ul>
   *
   * As more collectors appear, we'll need to update this set.
   */
  private static final ImmutableSet<String> KNOWN_YOUNG_GEN_NAMES =
      ImmutableSet.of("G1 Young Generation", "ParNew", "PS Scavenge", "Copy");

  /** The event bus to write events to. Only writes GC-related events. */
  private final BuckEventBus eventBus;

  /**
   * Registers for GC notifications to be sent to the given event bus.
   *
   * @param bus The event bus to send GC events to.
   */
  public static void register(BuckEventBus bus) {
    new GCNotificationEventEmitter(bus);
  }

  /**
   * Registers this event emitter with the JVM and subscribes itself to GC events, producing an
   * emitter that writes GC events to the given event bus. If the JVM doesn't support this, no
   * events are written.
   *
   * @param eventBus The event bus to write events to
   */
  GCNotificationEventEmitter(BuckEventBus eventBus) {
    this.eventBus = eventBus;

    // Despite there being one GC, there are often multiple GC beans corresponding to different
    // parts of the GC subsystem.
    // For G1, there are separate beans for the young and tenured generations, which we use to
    // determine the nature of a GC.
    for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (gcBean instanceof NotificationEmitter) {
        ((NotificationEmitter) gcBean).addNotificationListener(this, null, gcBean);
        LOG.info("Installed GC notification for GC " + gcBean.getName());
      }
    }
  }

  /**
   * Handles a JMX {@link Notification} emitted by one of the GC beans that we're listening to. This
   * function is called on a special runtime thread that is not visible to debuggers, so if you're
   * trying to break in this method and it's not working, that's why.
   *
   * <p>Since this is called on a special thread, it's important that it 1) not block and 2)
   * terminate as quickly as possible.
   *
   * @param notification The notification that we've just received from JMX
   * @param handback An instance of the {@link GarbageCollectorMXBean} that triggered the event,
   *     which we passed explicitly as the third parameter of {@link
   *     NotificationEmitter#addNotificationListener(NotificationListener, NotificationFilter,
   *     Object)} in the class constructor.
   */
  @Override
  public void handleNotification(Notification notification, Object handback) {
    // It's unlikely that the GC bean emits anything other than GC notifications, but the docs say
    // to check, so we do.
    if (notification
        .getType()
        .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION)) {
      GarbageCollectionNotificationInfo notificationInfo =
          GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
      GcInfo info = notificationInfo.getGcInfo();
      if (isMinorGCBean((GarbageCollectorMXBean) handback)) {
        eventBus.post(new GCMinorCollectionEvent(info));
      } else {
        eventBus.post(new GCMajorCollectionEvent(info));
      }
    }
  }

  /**
   * Returns whether or not the given gcBean represents the young generation of the GC that we are
   * using. Collections of young generations tend to be quick (on the order of milliseconds) and
   * it's useful to differentiate them from major collections in the event stream.
   *
   * <p>For G1, the name of the bean indicates whether or not it's the young generation bean, so we
   * use it here.
   *
   * @param gcBean The GC bean that just notified us
   * @return Whether or not the given GC bean represents a young gen
   */
  private static boolean isMinorGCBean(GarbageCollectorMXBean gcBean) {
    return KNOWN_YOUNG_GEN_NAMES.contains(gcBean.getName());
  }
}
