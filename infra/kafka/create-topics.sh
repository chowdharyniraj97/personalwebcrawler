#!/bin/sh
# Creates the crawler's Kafka topics. Safe to re-run: a topic that already exists is left as it is,
# even if its settings differ from the ones below.
set -eu

KAFKA_TOPICS=/opt/kafka/bin/kafka-topics.sh

# Kafka only deletes closed segments, so this must be smaller than retention.bytes.
SEGMENT_BYTES=33554432

create() {
  topic=$1
  shift
  "$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
    --topic "$topic" \
    --partitions "$PARTITIONS" \
    --replication-factor "$REPLICATION" \
    "$@"
}

create incoming \
  --config cleanup.policy=delete \
  --config retention.bytes="$RETENTION" \
  --config segment.bytes="$SEGMENT_BYTES"

create frontier-changelog \
  --config cleanup.policy=compact

"$KAFKA_TOPICS" --bootstrap-server "$BOOTSTRAP" --describe \
  --topic 'incoming|frontier-changelog' | grep PartitionCount
