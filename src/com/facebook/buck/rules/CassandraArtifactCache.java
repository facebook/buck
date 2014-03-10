/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.LogEvent;
import com.facebook.buck.event.ThrowableLogEvent;
import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.netflix.astyanax.AstyanaxContext;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.BadRequestException;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.Column;
import com.netflix.astyanax.model.ColumnFamily;
import com.netflix.astyanax.model.ColumnList;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class CassandraArtifactCache implements ArtifactCache {

  /**
   * If the user is offline, then we do not want to print every connection failure that occurs.
   * However, in practice, it appears that some connection failures can be intermittent, so we
   * should print enough to provide a signal of how flaky the connection is.
   */
  private static final int MAX_CONNECTION_FAILURE_REPORTS = 10;

  private static final String POOL_NAME = "ArtifactCachePool";
  private static final String CLUSTER_NAME = "BuckCacheCluster";
  private static final String KEYSPACE_NAME = "Buck";

  private static final String CONFIGURATION_COLUMN_FAMILY_NAME = "Configuration";
  private static final String CONFIGURATION_MAGIC_KEY = "magic";
  private static final String CONFIGURATION_MAGIC_VALUE = "Buck artifact cache";
  private static final String CONFIGURATION_TTL_KEY = "ttl";
  private static final String CONFIGURATION_COLUMN_NAME = "value";
  private static final ColumnFamily<String, String> CF_CONFIG = new ColumnFamily<String, String>(
      CONFIGURATION_COLUMN_FAMILY_NAME,
      StringSerializer.get(),
      StringSerializer.get());

  private static final String ARTIFACT_COLUMN_FAMILY_NAME = "Artifacts";
  private static final String ARTIFACT_COLUMN_NAME = "artifact";
  private static final ColumnFamily<String, String> CF_ARTIFACT = new ColumnFamily<String, String>(
      ARTIFACT_COLUMN_FAMILY_NAME,
      StringSerializer.get(),
      StringSerializer.get());

  private static final class KeyspaceAndTtl {
    private final Keyspace keyspace;
    private final int ttl;

    private Keyspace getKeyspace() {
      return keyspace;
    }

    private int getTtl() {
      return ttl;
    }

    private KeyspaceAndTtl(Keyspace keyspace, int ttl) {
      this.keyspace = keyspace;
      this.ttl = ttl;
    }
  }

  private final Future<KeyspaceAndTtl> keyspaceAndTtlFuture;
  private final AtomicInteger numConnectionExceptionReports;
  private final boolean doStore;
  private final BuckEventBus buckEventBus;

  private final Set<ListenableFuture<OperationResult<Void>>> futures;
  private final AtomicBoolean isWaitingToClose;

  public CassandraArtifactCache(String hosts, int port, boolean doStore, BuckEventBus buckEventBus)
      throws ConnectionException {
    this.doStore = doStore;
    this.buckEventBus = Preconditions.checkNotNull(buckEventBus);
    this.numConnectionExceptionReports = new AtomicInteger(0);

    final AstyanaxContext<Keyspace> context = new AstyanaxContext.Builder()
        .forCluster(CLUSTER_NAME)
        .forKeyspace(KEYSPACE_NAME)
        .withAstyanaxConfiguration(new AstyanaxConfigurationImpl()
            .setCqlVersion("3.0.0")
            .setTargetCassandraVersion("1.2")
            .setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE)
        )
        .withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl(POOL_NAME)
            .setSeeds(hosts)
            .setPort(port)
            .setMaxConnsPerHost(1)
        )
        .withConnectionPoolMonitor(new CountingConnectionPoolMonitor())
        .buildKeyspace(ThriftFamilyFactory.getInstance());

    ExecutorService connectionService = MoreExecutors.getExitingExecutorService(
        (ThreadPoolExecutor) Executors.newFixedThreadPool(1), 0, TimeUnit.SECONDS);
    this.keyspaceAndTtlFuture = connectionService.submit(new Callable<KeyspaceAndTtl>() {
      @Override
      public KeyspaceAndTtl call() throws ConnectionException {
        context.start();
        Keyspace keyspace = context.getClient();
        try {
          verifyMagic(keyspace);
          int ttl = getTtl(keyspace);
          return new KeyspaceAndTtl(keyspace, ttl);
        } catch (ConnectionException e) {
          reportConnectionFailure("Attempting to get keyspace and ttl from server.", e);
          throw e;
        }
      }
    });

    this.futures = Sets.newSetFromMap(
        new ConcurrentHashMap<ListenableFuture<OperationResult<Void>>, Boolean>());
    this.isWaitingToClose = new AtomicBoolean(false);
  }

  private static void verifyMagic(Keyspace keyspace) throws ConnectionException {
    OperationResult<ColumnList<String>> result;
    try {
      result = keyspace.prepareQuery(CF_CONFIG)
          .getKey(CONFIGURATION_MAGIC_KEY)
          .execute();
    } catch (BadRequestException e) {
      throw new HumanReadableException("Artifact cache error during schema verification: %s",
          e.getMessage());
    }
    Column<String> column = result.getResult().getColumnByName(CONFIGURATION_COLUMN_NAME);
    if (column == null || !column.getStringValue().equals(CONFIGURATION_MAGIC_VALUE)) {
      throw new HumanReadableException("Artifact cache schema mismatch");
    }
  }

  /**
   * @return The resulting keyspace and ttl of connecting to Cassandra if the connection succeeded,
   *    otherwise Optional.absent().  This method will block until connection finishes.
   */
  private Optional<KeyspaceAndTtl> getKeyspaceAndTtl() {
    try {
      return Optional.of(keyspaceAndTtlFuture.get());
    } catch (ExecutionException | InterruptedException e) {
      buckEventBus.post(ThrowableLogEvent.create(e,
          "Unexpected error when fetching keyspace and ttl: %s.",
          e.getMessage()));
    }
    return Optional.absent();
  }

  private static int getTtl(Keyspace keyspace) throws ConnectionException {
    OperationResult<ColumnList<String>> result = keyspace.prepareQuery(CF_CONFIG)
        .getKey(CONFIGURATION_TTL_KEY)
        .execute();
    Column<String> column = result.getResult().getColumnByName(CONFIGURATION_COLUMN_NAME);
    if (column == null) {
      throw new HumanReadableException("Artifact cache schema malformation.");
    }
    try {
      return Integer.parseInt(column.getStringValue());
    } catch (NumberFormatException e) {
      throw new HumanReadableException("Artifact cache ttl malformation: \"%s\".",
          column.getStringValue());
    }
  }

  @Override
  public CacheResult fetch(RuleKey ruleKey, File output) {
    Optional<KeyspaceAndTtl> keyspaceAndTtl = getKeyspaceAndTtl();
    if (!keyspaceAndTtl.isPresent()) {
      // Connecting to Cassandra failed, return false
      return CacheResult.MISS;
    }

    // Execute the query to Cassandra.
    OperationResult<ColumnList<String>> result;
    int ttl;
    try {
      Keyspace keyspace = keyspaceAndTtl.get().getKeyspace();
      ttl = keyspaceAndTtl.get().getTtl();

      result = keyspace.prepareQuery(CF_ARTIFACT)
          .getKey(ruleKey.toString())
          .execute();
    } catch (ConnectionException e) {
      reportConnectionFailure("Attempting to fetch " + ruleKey + ".", e);
      return CacheResult.MISS;
    }

    CacheResult success = CacheResult.MISS;
    try {
      Column<String> column = result.getResult().getColumnByName(ARTIFACT_COLUMN_NAME);
      if (column != null) {
        byte[] artifact = column.getByteArrayValue();
        Files.createParentDirs(output);
        Files.write(artifact, output);
        // Cassandra timestamps use microsecond resolution.
        if (System.currentTimeMillis() * 1000L - column.getTimestamp() > ttl * 1000000L / 2L) {
          // The cache entry has lived for more than half of its total TTL, so rewrite it in order
          // to reset the TTL.
          store(ruleKey, output);
        }
        success = CacheResult.CASSANDRA_HIT;
      }
    } catch (IOException e) {
      buckEventBus.post(ThrowableLogEvent.create(e,
          "Artifact was fetched but could not be written: %s at %s.",
          ruleKey,
          output.getPath()));
    }

    buckEventBus.post(LogEvent.fine("Artifact fetch(%s, %s) cache %s",
        ruleKey,
        output.getPath(),
        (success.isSuccess() ? "hit" : "miss")));
    return success;
  }

  @Override
  public void store(RuleKey ruleKey, File output) {
    if (!isStoreSupported()) {
      return;
    }

    Optional<KeyspaceAndTtl> keyspaceAndTtl = getKeyspaceAndTtl();
    if (!keyspaceAndTtl.isPresent()) {
      return;
    }
    try {
      Keyspace keyspace = keyspaceAndTtl.get().getKeyspace();
      int ttl = keyspaceAndTtl.get().getTtl();
      MutationBatch mutationBatch = keyspace.prepareMutationBatch();

      mutationBatch.withRow(CF_ARTIFACT, ruleKey.toString())
          .setDefaultTtl(ttl)
          .putColumn(ARTIFACT_COLUMN_NAME, Files.toByteArray(output));
      ListenableFuture<OperationResult<Void>> mutationFuture = mutationBatch.executeAsync();
      trackFuture(mutationFuture);
    } catch (ConnectionException e) {
      reportConnectionFailure("Attempting to store " + ruleKey + ".", e);
    } catch (IOException | OutOfMemoryError e) {
      buckEventBus.post(ThrowableLogEvent.create(e,
          "Artifact store(%s, %s) error: %s",
          ruleKey,
          output.getPath()));
    }
  }

  private void trackFuture(final ListenableFuture<OperationResult<Void>> future) {
    futures.add(future);
    Futures.addCallback(future, new FutureCallback<OperationResult<Void>>() {
      @Override
      public void onSuccess(OperationResult<Void> result) {
        removeFuture();
      }

      @Override
      public void onFailure(Throwable t) {
        removeFuture();
      }

      private void removeFuture() {
        if (!isWaitingToClose.get()) {
          futures.remove(future);
        }
      }
    });
  }

  @Override
  @SuppressWarnings("PMD.EmptyCatchBlock")
  public void close() {
    isWaitingToClose.set(true);
    ListenableFuture<List<OperationResult<Void>>> future = Futures.allAsList(futures);
    try {
      future.get();
    } catch (InterruptedException | ExecutionException e) {
      // Swallow exception and move on.
    }
  }

  @Override
  public boolean isStoreSupported() {
    return doStore;
  }

  private void reportConnectionFailure(String context, ConnectionException exception) {
    if (numConnectionExceptionReports.incrementAndGet() < MAX_CONNECTION_FAILURE_REPORTS) {
      buckEventBus.post(ThrowableLogEvent.create(exception,
          "%s Connecting to cassandra failed: %s.",
          context,
          exception.getMessage()));
    }
  }
}
