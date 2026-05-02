/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j;

import java.io.File;
import java.net.URL;

/**
 * Utility for locating test resource files on the filesystem.
 *
 * <p>Resources are resolved via the classloader so that both Gradle ({@code build/resources/test})
 * and Maven ({@code target/test-classes}) layouts work without hardcoded paths.
 */
public final class TestResources {

    private TestResources() {}

    /**
     * Returns a {@link File} for the named classpath resource.
     *
     * @param name resource name, e.g. {@code "input.ps"}
     * @return absolute {@code File} pointing to the resource
     * @throws IllegalArgumentException if the resource is not found on the classpath
     * @throws RuntimeException wrapping {@link java.net.URISyntaxException} if the URL cannot be
     *     converted to a URI (should never happen for file-system resources)
     */
    public static File get(String name) {
        URL url = TestResources.class.getClassLoader().getResource(name);
        if (url == null) {
            throw new IllegalArgumentException(
                    "Test resource not found on classpath: "
                            + name
                            + " — ensure the file exists in src/test/resources/");
        }
        try {
            return new File(url.toURI());
        } catch (java.net.URISyntaxException e) {
            throw new RuntimeException("Could not convert resource URL to File: " + url, e);
        }
    }
}
