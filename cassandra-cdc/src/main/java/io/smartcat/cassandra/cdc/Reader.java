package io.smartcat.cassandra.cdc;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.commitlog.CommitLogReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;

public class Reader {

    private static final Logger LOGGER = LoggerFactory.getLogger(CustomCommitLogReadHandler.class);

    private final WatchService watcher;
    private final Path dir;
    private final WatchKey key;
    private final CommitLogReader commitLogReader;
    private final CustomCommitLogReadHandler commitLogReadHander;

    /**
     * Creates a WatchService and registers the given directory
     */
    public Reader(Map<String, Object> configuration) throws IOException {
        this.dir = Paths.get((String) YamlUtils.select(configuration, "cassandra.cdc_raw_directory"));
        watcher = FileSystems.getDefault().newWatchService();
        key = dir.register(watcher, ENTRY_CREATE);
        commitLogReader = new CommitLogReader();
        commitLogReadHander = new CustomCommitLogReadHandler(configuration);
        DatabaseDescriptor.toolInitialization();
        Schema.instance.loadFromDisk(false);
    }

    /**
     * Process all events for keys queued to the watcher
     *
     * @throws InterruptedException
     * @throws IOException
     */
    public void processEvents() throws InterruptedException, IOException {
        while (true) {
            WatchKey aKey = watcher.take();
            if (!key.equals(aKey)) {
                LOGGER.error("WatchKey not recognized.");
                continue;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind != ENTRY_CREATE) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path relativePath = ev.context();
                Path absolutePath = dir.resolve(relativePath);
                processCommitLogSegment(absolutePath);
                Files.delete(absolutePath);

                // print out event
                LOGGER.debug("{}: {}", event.kind().name(), absolutePath);
            }
            key.reset();
        }
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Map<String, Object> configuration = YamlUtils.load(args[0]);
        new Reader(configuration).processEvents();
    }

    private void processCommitLogSegment(Path path) throws IOException {
        LOGGER.debug("Processing commitlog segment...");
        commitLogReader.readCommitLogSegment(commitLogReadHander, path.toFile(), false);
        LOGGER.debug("Commitlog segment processed.");
    }
}
