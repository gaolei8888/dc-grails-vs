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

    /**
     * Class name patterns covering the project's own code, for a class filter.
     *
     * <p>Taken from the directories: a package is a directory under a source root,
     * so {@code grails-app/services/dapspike} means {@code dapspike.*}. Only the
     * first segment is used, which is broader than necessary and exactly what a
     * filter wants -- it has to match the closure classes and the methods Grails
     * generates as well as the ones in the file.
     *
     * <p>Used where a filter is the only affordable way to ask a question of every
     * method entered: unfiltered, a single Grails request enters tens of thousands
     * of methods.
     */
    public synchronized List<String> packageFilters() {
        List<String> filters = new ArrayList<>();
        for (Path root : roots) {
            try (java.util.stream.Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .map(child -> child.getFileName().toString())
                        .filter(SourceLocator::looksLikePackage)
                        .map(name -> name + ".*")
                        .filter(filter -> !filters.contains(filter))
                        .forEach(filters::add);
            } catch (Exception e) {
                // an unreadable root is not a reason to fail the step
            }
        }
        return filters;
    }

    private static boolean looksLikePackage(String name) {
        if (name.isEmpty() || !Character.isLowerCase(name.charAt(0))) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
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
