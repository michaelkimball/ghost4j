/*
 * Ghost4J: a Java wrapper for Ghostscript API.
 *
 * Distributable under LGPL license.
 * See terms of license at http://www.gnu.org/licenses/lgpl.html.
 */
package org.ghost4j.renderer;

/**
 * Interface defining a remote renderer (for Ghostscript multi process support).
 *
 * @author Gilles Grousset (gi.grousset@gmail.com)
 */
public interface RemoteRenderer extends Renderer {

    /**
     * Sets max parallel rendering processes allowed for the renderer
     *
     * @param maxProcessCount
     */
    public void setMaxProcessCount(int maxProcessCount);
}
