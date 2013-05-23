-- Initialize a Cassandra node for use as a buck artifact cache. Invoke this
-- script as:
--
--   cassandra-cli -h <node_hostname> -f init_cassandra_node.cql
--
-- Depending on cluster configuration, this script should be modified prior to
-- invocation to increase replication_factor.

CREATE KEYSPACE Buck
  WITH placement_strategy = 'org.apache.cassandra.locator.SimpleStrategy'
  AND strategy_options = {replication_factor:2};
USE Buck;

CREATE COLUMN FAMILY Configuration
  WITH comparator = 'UTF8Type'
  AND key_validation_class = 'UTF8Type'
  AND column_metadata = [
    {column_name: value, validation_class: 'UTF8Type'}
  ];

SET Configuration['magic']['value'] = 'Buck artifact cache';
SET Configuration['schema']['value'] = '0';
-- Set cache ttl to one week.
SET Configuration['ttl']['value'] = '604800';

CREATE COLUMN FAMILY Artifacts
  WITH comparator = 'UTF8Type'
  AND key_validation_class = 'UTF8Type'
  AND column_metadata = [
    {column_name: artifact, validation_class: 'BytesType'}
  ];
