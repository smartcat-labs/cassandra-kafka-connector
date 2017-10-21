package io.smartcat.cassandra.cdc;

import org.apache.cassandra.io.util.FileUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YamlUtils {

    private static final Pattern PATH_INDEX = Pattern.compile("^(.+)\\[([0-9]+)\\]$");

    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(String filePath) {
        InputStream stream = null;
        try {
            stream = new FileInputStream(new File(filePath));
            Yaml yaml = new Yaml();
            return (Map<String, Object>) yaml.load(stream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FileUtils.closeQuietly(stream);
        }
    }

    public static Object select(Map<String, Object> configuration, String path) {
        List<Object> pathComponents = toPathList(path);
        if (pathComponents.isEmpty()) {
            throw new IllegalArgumentException(
                    String.format("Given path expression %s has to have at least one component.", pathComponents));
        }
        return extractPathRecursive(configuration, pathComponents);
    }

    private static Object extractPathRecursive(Object input, List<Object> path) {
        if (path.isEmpty()) {
            return input;
        } else {
            Object obj;
            Object firstElement = path.get(0);
            if (firstElement instanceof Integer) {
                Integer childIndex = (Integer) firstElement;
                obj = ((List<?>) input).get(childIndex);
            } else {
                String childName = (String) firstElement;
                obj = ((Map<?, ?>) input).get(childName);
            }
            return extractPathRecursive(obj, path.subList(1, path.size()));
        }
    }

    private static List<Object> toPathList(String jsonPath) {
        String[] pathComponents = jsonPath.split("\\.");
        List<Object> pathList = new ArrayList<>();
        for (String component : pathComponents) {
            Matcher m = PATH_INDEX.matcher(component);
            if (m.matches()) {
                pathList.add(m.group(1));
                pathList.add(Integer.valueOf(m.group(2)));
            } else {
                pathList.add(component);
            }
        }
        return pathList;
    }
}
