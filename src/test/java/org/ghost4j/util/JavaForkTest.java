/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.ghost4j.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 
 * @author ggrousset
 */
public class JavaForkTest {

    @BeforeEach
    protected void setUp() throws Exception {
    }

    @AfterEach
    protected void tearDown() throws Exception {
    }

    /**
     * Test of start method, of class JavaFork.
     */
    @Test
    public void testStart() throws Exception {

	// create fork
	JavaFork fork = new JavaFork();
	fork.setRedirectStreams(true);
	fork.setWaitBeforeExiting(true);
	fork.setStartClass(ForkTest.class);

	// run
	fork.start();

    }

}
