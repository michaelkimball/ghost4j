/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */

package org.ghost4j;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * AbstractComponent tests.
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public class AbstractComponentTest {

    @BeforeEach
    protected void setUp() throws Exception {}

    @AfterEach
    protected void tearDown() throws Exception {}

    @Test
    public void testIsDeviceSupported() throws Exception {

        AbstractComponent component = new AbstractComponent() {};

        // pdfwrite should be available in every Ghostscript version
        assertTrue(component.isDeviceSupported("pdfwrite"));
    }

    @Test
    public void testIsDeviceSupportedWithNonExistingDevice() throws Exception {

        AbstractComponent component = new AbstractComponent() {};

        assertFalse(component.isDeviceSupported("nonexistingdevice"));
    }
}
