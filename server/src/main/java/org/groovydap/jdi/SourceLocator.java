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
    /** Class name a GSP compiles into, to the GSP. Built on first use. */
    private Map<String, Path> gspPages;
    /** The same pages by the part of the name a deployment does not change. */
    private final Map<String, Path> gspMarkers = new HashMap<>();

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

        Path found = findGsp(relative);
        for (Path root : roots) {
            if (found != null) {
                break;
            }
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                found = candidate;
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

    /**
     * The GSPs under the roots, indexed by the class name each compiles into.
     *
     * <p>A frame in a page reports {@code sourcePath()} as that class name -- the
     * page's own path with every non-alphanumeric character turned into an
     * underscore -- which resolves against no root and matches no file name, so
     * the ordinary lookup cannot find it. Walking the views once and mangling each
     * name the same way turns the lookup back into a map read.
     */
    /**
     * The GSP a frame came from, by class name.
     *
     * <p>Exactly first, then by the part of the name that does not change with
     * where the application runs: a page compiled from a war is named
     * ServletContext_resource___WEB_INF_grails_app_views_spike_page_gsp_ where the
     * same page from the sources is C__Users_..._views_spike_page_gsp. Only an
     * unambiguous match counts -- two pages that end the same way name neither.
     */
    private synchronized Path findGsp(String className) {
        Map<String, Path> pages = gspPages();
        Path exact = pages.get(className.toLowerCase(java.util.Locale.ROOT));
        if (exact != null) {
            return exact;
        }
        Path single = null;
        for (Map.Entry<String, Path> page : gspMarkers.entrySet()) {
            if (className.toLowerCase(java.util.Locale.ROOT).contains(page.getKey())) {
                if (single != null) {
                    return null;
                }
                single = page.getValue();
            }
        }
        return single;
    }

    private synchronized Map<String, Path> gspPages() {
        if (gspPages != null) {
            return gspPages;
        }
        gspPages = new HashMap<>();
        for (Path root : roots) {
            try (java.util.stream.Stream<Path> walk = Files.walk(root, 12)) {
                walk.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString()
                            .toLowerCase(java.util.Locale.ROOT).endsWith(".gsp"))
                    .forEach(file -> {
                        gspPages.put(GspSource.classNameFor(file)
                                .toLowerCase(java.util.Locale.ROOT), file);
                        String marker = GspSource.relativeMarker(file);
                        if (marker != null) {
                            gspMarkers.put(marker.toLowerCase(java.util.Locale.ROOT), file);
                        }
                    });
            } catch (Exception e) {
                // an unreadable root indexes nothing; not an error
            }
        }
        return gspPages;
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
