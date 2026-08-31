package org.groovydap.jdi;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Location;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the file a stack frame came from.
 *
 * <p>This direction was never the broken one -- the JVM reports
 * {@code sourcePath()} as {@code com/example/FooService.groovy} and the adapter
 * only has to say which root it lives under. Roots come from the launch
 * configuration; for a Grails project they are the {@code grails-app} artefact
 * directories plus {@code src/main/groovy}.
 */
public final class SourceLocator {

    private final List<Path> roots = new ArrayList<>();
    private final Map<String, Path> cache = new HashMap<>();

    public SourceLocator(List<String> configuredRoots) {
        if (configuredRoots != null) {
            for (String root : configuredRoots) {
                Path path = Paths.get(root);
                if (Files.isDirectory(path)) {
                    roots.add(path);
                }
            }
        }
    }

    /** The absolute path of the source for this location, or null. */
    public synchronized Path find(Location location) {
        String relative;
        try {
            relative = location.sourcePath();
        } catch (AbsentInformationException e) {
            return null;
        }
        if (relative == null || relative.isEmpty()) {
            return null;
        }
        if (cache.containsKey(relative)) {
            return cache.get(relative);
        }

        Path found = null;
        for (Path root : roots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                found = candidate;
                break;
            }
        }
        if (found == null) {
            // Groovy writes the source path without the package directories when
            // the class is a script, so fall back to matching on the file name.
            String fileName = Paths.get(relative).getFileName().toString();
            for (Path root : roots) {
                Path candidate = findByName(root, fileName);
                if (candidate != null) {
                    found = candidate;
                    break;
                }
            }
        }
        cache.put(relative, found);
        return found;
    }

    private Path findByName(Path root, String fileName) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root, 12)) {
            return walk.filter(p -> p.getFileName().toString().equals(fileName))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
