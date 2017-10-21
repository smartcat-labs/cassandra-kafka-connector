# Cassandra Capture Data Change (CDC)

Purpose of this project is to serve as an example for how to implement read Cassandra CDC.

## Create a JAR

This is a common Maven project with shade plugin to include all dependencies into JAR. To build it, just run:

`mvn clean install`

## Use JAR

In order to start reading CDC commitlogs, run JAR with:
`java -jar cassandra-cdc-0.0.1-SNAPSHOT.jar <path to cdc_raw_directory> <path to configuration>`

create trigger in cassandra, JAR file needs to be places under `$CASSANDRA_CONFIG/triggers` directory on every node which will be used as coordinator. Also, path to `KafkaTrigger.yml` (line 37) needs to be adjusted to location where actuall `KafkaTrigger.yml` file is placed. Content of the file should be:

```
bootstrap.servers: cluster_kafka_1:9092,cluster_kafka_2:9092
topic.name: trigger-topic
```

Note that content matches infrastructure setup which is created using `docker-compose` command from `cluster` directory. Docker compose file used is:

```
version: '3.3'
services:
  zookeeper:
    image: wurstmeister/zookeeper:3.4.6
    ports:
      - "2181:2181"
  kafka:
    image: wurstmeister/kafka:0.10.1.1
    ports:
      - 9092
    environment:
      HOSTNAME_COMMAND: "ifconfig | awk '/Bcast:.+/{print $$2}' | awk -F\":\" '{print $$2}'"
      KAFKA_ADVERTISED_PORT: 9092
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
  cassandra-seed:
    image: cassandra-cdc
    ports:
      - 7199
      - 9042
    environment:
      CASSANDRA_CLUSTER_NAME: test-cluster
  cassandra:
    image: cassandra-cdc
    ports:
      - 7199
      - 9042
    environment:
      CASSANDRA_CLUSTER_NAME: test-cluster
      CASSANDRA_SEEDS: cassandra-seed
```

`cassandra-cdc` docker image is custom built for this usage. It includes changes to configuration file, JAR file of reader application and its configuration files.
Whole setup necessary for building `cassandra-cdc` docker image can be found in [docker](docker) directory.

```
FROM cassandra:3.11.0
COPY KafkaTrigger.yml /etc/cassandra/triggers/KafkaTrigger.yml
COPY cassandra-trigger-0.0.1-SNAPSHOT.jar /etc/cassandra/triggers/trigger.jar
CMD ["cassandra", "-f"]
```

If you intend using JAR file in different infrastructure setup (virtual machines, different docker setup, cloud environment) configuration needs to be changed to match that infrastructure.

## Create a trigger

To add a trigger to a table, just execute `CREATE TRIGGER kafka_trigger ON movies_by_genre USING 'io.smartcat.cassandra.trigger.KafkaTrigger';`.