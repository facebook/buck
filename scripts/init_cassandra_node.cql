-- Initialize a Cassandra node for use as a buck artifact cache. Invoke this
-- script as:
--
--   cassandra-cli -h <node_hostname> -f init_cassandra_node.cql

CREATE KEYSPACE Buck;
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
