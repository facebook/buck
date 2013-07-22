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

import com.facebook.buck.util.HumanReadableException;
import com.google.common.base.Optional;
import com.google.common.io.Files;
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
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class CassandraArtifactCache implements ArtifactCache {
  private static final Logger logger = Logger.getLogger(CassandraArtifactCache.class.getName());
  private static final String poolName = "ArtifactCachePool";
  private static final String clusterName = "BuckCacheCluster";
  private static final String keyspaceName = "Buck";

  private static final String configurationColumnFamilyName = "Configuration";
  private static final String configurationMagicKey = "magic";
  private static final String configurationMagicValue = "Buck artifact cache";
  private static final String configurationTtlKey = "ttl";
  private static final String configurationColumnName = "value";
  private static final ColumnFamily<String, String> CF_CONFIG = new ColumnFamily<String, String>(
      configurationColumnFamilyName,
      StringSerializer.get(),
      StringSerializer.get());

  private static final String artifactColumnFamilyName = "Artifacts";
  private static final String artifactColumnName = "artifact";
  private static final ColumnFamily<String, String> CF_ARTIFACT = new ColumnFamily<String, String>(
      artifactColumnFamilyName,
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
  private final boolean doStore;

  public CassandraArtifactCache(String hosts, int port, boolean doStore)
      throws ConnectionException {
    this.doStore = doStore;

    final AstyanaxContext<Keyspace> context = new AstyanaxContext.Builder()
        .forCluster(clusterName)
        .forKeyspace(keyspaceName)
        .withAstyanaxConfiguration(new AstyanaxConfigurationImpl()
            .setCqlVersion("3.0.0")
            .setTargetCassandraVersion("1.2")
            .setDiscoveryType(NodeDiscoveryType.RING_DESCRIBE)
        )
        .withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl(poolName)
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
        verifyMagic(keyspace);
        int ttl = getTtl(keyspace);
        return new KeyspaceAndTtl(keyspace, ttl);
      }
    });
  }

  private static void verifyMagic(Keyspace keyspace) throws ConnectionException {
    OperationResult<ColumnList<String>> result;
    try {
      result = keyspace.prepareQuery(CF_CONFIG)
          .getKey(configurationMagicKey)
          .execute();
    } catch (BadRequestException e) {
      throw new HumanReadableException("Artifact cache error during schema verification: %s",
          e.getMessage());
    }
    Column<String> column = result.getResult().getColumnByName(configurationColumnName);
    if (column == null || !column.getStringValue().equals(configurationMagicValue)) {
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
    } catch (InterruptedException | ExecutionException e) {
      // TODO (je) Fire an event that Cassandra is failing.
      logger.warning(String.format("Connecting to cassandra failed"));
    }
    return Optional.absent();
  }

  private static int getTtl(Keyspace keyspace) throws ConnectionException {
    OperationResult<ColumnList<String>> result = keyspace.prepareQuery(CF_CONFIG)
        .getKey(configurationTtlKey)
        .execute();
    Column<String> column = result.getResult().getColumnByName(configurationColumnName);
    if (column == null) {
      throw new HumanReadableException("Artifact cache schema malformation");
    }
    try {
      return Integer.parseInt(column.getStringValue());
    } catch (NumberFormatException e) {
      throw new HumanReadableException("Artifact cache ttl malformation: \"%s\"",
          column.getStringValue());
    }
  }

  @Override
  public boolean fetch(RuleKey ruleKey, File output) {
    if (!ruleKey.isIdempotent()) {
      return false;
    }
    Optional<KeyspaceAndTtl> keyspaceAndTtl = getKeyspaceAndTtl();
    if (!keyspaceAndTtl.isPresent()) {
      // Connecting to Cassandra failed, return false
      return false;
    }
    boolean success = false;
    try {
      Keyspace keyspace = keyspaceAndTtl.get().getKeyspace();
      int ttl = keyspaceAndTtl.get().getTtl();

      OperationResult<ColumnList<String>> result = keyspace.prepareQuery(CF_ARTIFACT)
          .getKey(ruleKey.toString())
          .execute();
      Column<String> column = result.getResult().getColumnByName(artifactColumnName);
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
        success = true;
      }
    } catch (Exception e) {
      logger.warning(String.format("Artifact fetch(%s, %s) error: %s",
          ruleKey,
          output.getPath(),
          e.getMessage()));
    }
    logger.info(String.format("Artifact fetch(%s, %s) cache %s",
        ruleKey,
        output.getPath(),
        (success ? "hit" : "miss")));
    return success;
  }

  @Override
  public void store(RuleKey ruleKey, File output) {
    if (!doStore || !ruleKey.isIdempotent()) {
      return;
    }

    Optional<KeyspaceAndTtl> keyspaceAndTtl = getKeyspaceAndTtl();
    if (!keyspaceAndTtl.isPresent()) {
      return;
    }
    try {
      Keyspace keyspace = keyspaceAndTtl.get().getKeyspace();
      int ttl = keyspaceAndTtl.get().getTtl();
      MutationBatch m = keyspace.prepareMutationBatch();

      m.withRow(CF_ARTIFACT, ruleKey.toString())
          .setDefaultTtl(ttl)
          .putColumn(artifactColumnName, Files.toByteArray(output));
      m.executeAsync();
    } catch (IOException | ConnectionException | OutOfMemoryError e) {
      logger.warning(String.format("Artifact store(%s, %s) error: %s",
          ruleKey,
          output.getPath(),
          e.getMessage()));
    }
  }
}
