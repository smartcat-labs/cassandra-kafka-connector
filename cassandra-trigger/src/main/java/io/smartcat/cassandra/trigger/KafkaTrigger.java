package io.smartcat.cassandra.trigger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.ClusteringBound;
import org.apache.cassandra.db.ClusteringPrefix;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.partitions.Partition;
import org.apache.cassandra.db.rows.Cell;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.triggers.ITrigger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.json.simple.JSONObject;
import org.yaml.snakeyaml.Yaml;

public class KafkaTrigger implements ITrigger {

    private static final String FILE_PATH = "/etc/cassandra/triggers/KafkaTrigger.yml";
    private static final String TOPIC_NAME = "topic.name";

    private final String topic;
    private final Producer<String, String> producer;
    private final ThreadPoolExecutor threadPoolExecutor;

    public KafkaTrigger() {
        Map<String, Object> configuration = loadConfiguration();
        topic = (String) getProperty(TOPIC_NAME, configuration);
        StringSerializer keySerializer = getSerializer(configuration, true);
        StringSerializer valueSerializer = getSerializer(configuration, false);
        producer = new KafkaProducer<>(configuration, keySerializer, valueSerializer);
        threadPoolExecutor = new ThreadPoolExecutor(4, 20, 30, TimeUnit.SECONDS, new LinkedBlockingDeque<>());
    }

    @Override
    public Collection<Mutation> augment(Partition partition) {
        threadPoolExecutor.execute(() -> readPartition(partition));
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private void readPartition(Partition partition) {
        String key = getKey(partition);
        JSONObject obj = new JSONObject();
        obj.put("key", key);
        if (partitionIsDeleted(partition)) {
            obj.put("partitionDeleted", true);
        } else {
            UnfilteredRowIterator it = partition.unfilteredIterator();
            List<JSONObject> rows = new ArrayList<>();
            while (it.hasNext()) {
                Unfiltered un = it.next();
                if (un.isRow()) {
                    JSONObject jsonRow = new JSONObject();
                    Clustering clustering = (Clustering) un.clustering();
                    String clusteringKey = clustering.toCQLString(partition.metadata());
                    jsonRow.put("clusteringKey", clusteringKey);
                    Row row = partition.getRow(clustering);

                    if (rowIsDeleted(row)) {
                        obj.put("rowDeleted", true);
                    } else {
                        Iterator<Cell> cells = row.cells().iterator();
                        Iterator<ColumnDefinition> columns = row.columns().iterator();
                        List<JSONObject> cellObjects = new ArrayList<>();
                        while (cells.hasNext() && columns.hasNext()) {
                            JSONObject jsonCell = new JSONObject();
                            ColumnDefinition columnDef = columns.next();
                            Cell cell = cells.next();
                            jsonCell.put("name", columnDef.name.toString());
                            if (cell.isTombstone()) {
                                jsonCell.put("deleted", true);
                            } else {
                                String data = columnDef.type.getString(cell.value());
                                jsonCell.put("value", data);
                            }
                            cellObjects.add(jsonCell);
                        }
                        jsonRow.put("cells", cellObjects);
                    }
                    rows.add(jsonRow);
                } else if (un.isRangeTombstoneMarker()) {
                    obj.put("rowRangeDeleted", true);
                    ClusteringBound bound = (ClusteringBound) un.clustering();
                    List<JSONObject> bounds = new ArrayList<>();
                    for (int i = 0; i < bound.size(); i++) {
                        String clusteringBound = partition.metadata().comparator.subtype(i).getString(bound.get(i));
                        JSONObject boundObject = new JSONObject();
                        boundObject.put("clusteringKey", clusteringBound);
                        if (i == bound.size() - 1) {
                            if (bound.kind().isStart()) {
                                boundObject.put("inclusive",
                                        bound.kind() == ClusteringPrefix.Kind.INCL_START_BOUND ? true : false);
                            }
                            if (bound.kind().isEnd()) {
                                boundObject.put("inclusive",
                                        bound.kind() == ClusteringPrefix.Kind.INCL_END_BOUND ? true : false);
                            }
                        }
                        bounds.add(boundObject);
                    }
                    obj.put((bound.kind().isStart() ? "start" : "end"), bounds);
                }
            }
            obj.put("rows", rows);
        }
        String value = obj.toJSONString();
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        producer.send(record);
    }

    private boolean partitionIsDeleted(Partition partition) {
        return partition.partitionLevelDeletion().markedForDeleteAt() > Long.MIN_VALUE;
    }

    private boolean rowIsDeleted(Row row) {
        return row.deletion().time().markedForDeleteAt() > Long.MIN_VALUE;
    }

    private String getKey(Partition partition) {
        return partition.metadata().getKeyValidator().getString(partition.partitionKey().getKey());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadConfiguration() {
        InputStream stream = null;
        try {
            stream = new FileInputStream(new File(FILE_PATH));
            Yaml yaml = new Yaml();
            return (Map<String, Object>) yaml.load(stream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FileUtils.closeQuietly(stream);
        }
    }

    private Object getProperty(String key, Map<String, Object> configuration) {
        if (!configuration.containsKey(key)) {
            throw new RuntimeException("Property: " + key + " not found in configuration.");
        }
        return configuration.get(key);
    }

    private StringSerializer getSerializer(Map<String, Object> configuration, boolean isKey) {
        StringSerializer serializer = new StringSerializer();
        serializer.configure(configuration, isKey);
        return serializer;
    }
}
