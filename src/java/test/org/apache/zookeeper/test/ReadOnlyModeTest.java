/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zookeeper.test;

import java.io.ByteArrayOutputStream;
import java.io.LineNumberReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import junit.framework.Assert;

import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.WriterAppender;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NotReadOnlyException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.apache.zookeeper.ZKTestCase;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.ZooKeeper.States;
import org.apache.zookeeper.test.ClientBase.CountdownWatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ReadOnlyModeTest extends ZKTestCase {
    private static int CONNECTION_TIMEOUT = QuorumBase.CONNECTION_TIMEOUT;
    private QuorumUtil qu = new QuorumUtil(1);

    @Before
    public void setUp() throws Exception {
        qu.startQuorum();
    }

    @After
    public void tearDown() throws Exception {
        qu.tearDown();
    }

    /**
     * Basic test of read-only client functionality. Tries to read and write
     * during read-only mode, then regains a quorum and tries to write again.
     */
    @Test
    public void testReadOnlyClient() throws Exception {
        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT); // ensure zk got connected

        final String data = "Data to be read in RO mode";
        final String node = "/tnode";
        zk.create(node, data.getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);

        watcher.reset();
        qu.shutdown(2);
        watcher.waitForConnected(CONNECTION_TIMEOUT);

        // read operation during r/o mode
        String remoteData = new String(zk.getData(node, false, null));
        Assert.assertEquals(data, remoteData);

        try {
            zk.setData(node, "no way".getBytes(), -1);
            Assert.fail("Write operation has succeeded during RO mode");
        } catch (NotReadOnlyException e) {
            // ok
        }

        watcher.reset();
        qu.start(2);
        Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(
                "127.0.0.1:" + qu.getPeer(2).clientPort, CONNECTION_TIMEOUT));
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        zk.setData(node, "We're in the quorum now".getBytes(), -1);

        zk.close();
    }

    /**
     * Ensures that upon connection to a read-only server client receives
     * ConnectedReadOnly state notification.
     */
    @Test
    public void testConnectionEvents() throws Exception {
        final List<KeeperState> states = new ArrayList<KeeperState>();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                new Watcher() {
                    public void process(WatchedEvent event) {
                        states.add(event.getState());
                    }
                }, true);
        boolean success = false;
        for (int i = 0; i < 30; i++) {
            try {
                zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
                success=true;
                break;
            } catch(KeeperException.ConnectionLossException e) {
                Thread.sleep(1000);               
            }            
        }
        Assert.assertTrue("Did not succeed in connecting in 30s", success);

        // kill peer and wait no more than 10 seconds for read-only server
        // to be started (which should take one tickTime (2 seconds) + 2 seconds
        // of grace wait time for the read-only server to start.) (total 4 seconds 
        // and any context switching.)
        qu.shutdown(2);
        long start = System.currentTimeMillis();
        while (!(zk.getState() == States.CONNECTEDREADONLY)) {
            Thread.sleep(200);
            Assert.assertTrue("Can't connect to the server", System
                    .currentTimeMillis()
                    - start < 10000);
        }

        // At this point states list should contain, in the given order,
        // SyncConnected, Disconnected, and ConnectedReadOnly states
        Assert.assertTrue("ConnectedReadOnly event wasn't received", states
                .get(2) == KeeperState.ConnectedReadOnly);
        zk.close();
    }

    /**
     * Tests a situation when client firstly connects to a read-only server and
     * then connects to a majority server. Transition should be transparent for
     * the user.
     */
    @Test
    public void testSessionEstablishment() throws Exception {
        qu.shutdown(2);

        CountdownWatcher watcher = new CountdownWatcher();
        ZooKeeper zk = new ZooKeeper(qu.getConnString(), CONNECTION_TIMEOUT,
                watcher, true);
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        Assert.assertSame("should be in r/o mode", States.CONNECTEDREADONLY, zk
                .getState());
        long fakeId = zk.getSessionId();

        watcher.reset();
        qu.start(2);
        Assert.assertTrue("waiting for server up", ClientBase.waitForServerUp(
                "127.0.0.1:" + qu.getPeer(2).clientPort, CONNECTION_TIMEOUT));
        watcher.waitForConnected(CONNECTION_TIMEOUT);
        zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT);
        Assert.assertFalse("fake session and real session have same id", zk
                .getSessionId() == fakeId);

        zk.close();
    }

    /**
     * Ensures that client seeks for r/w servers while it's connected to r/o
     * server.
     */
    @SuppressWarnings("deprecation")
    @Test
    public void testSeekForRwServer() throws Exception {

        // setup the logger to capture all logs
        // Don't rely on an external configuration of logging to ensure
        // this test succeeds. This test requires a specific layout in 
        // order to validate success, so explicitly set the layout.
        //        Layout layout = Logger.getRootLogger().getAppender("CONSOLE")
        //                .getLayout();        
        Layout layout = 
            new PatternLayout("%d{ISO8601} [myid:%X{myid}] - %-5p [%t:%C{1}@%L] - %m%n");
        
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        WriterAppender appender = new WriterAppender(layout, os);
        appender.setImmediateFlush(true);
        appender.setThreshold(Level.INFO);
        Logger zlogger = Logger.getLogger("org.apache.zookeeper");
        zlogger.addAppender(appender);

        try {
            qu.shutdown(2);
            CountdownWatcher watcher = new CountdownWatcher();
            ZooKeeper zk = new ZooKeeper(qu.getConnString(),
                    CONNECTION_TIMEOUT, watcher, true);
            watcher.waitForConnected(CONNECTION_TIMEOUT);

            // if we don't suspend a peer it will rejoin a quorum
            qu.getPeer(1).peer.suspend();

            // start two servers to form a quorum; client should detect this and
            // connect to one of them
            watcher.reset();
            qu.start(2);
            qu.start(3);
            ClientBase.waitForServerUp(qu.getConnString(), 2000);
            watcher.waitForConnected(CONNECTION_TIMEOUT);
            zk.create("/test", "test".getBytes(), ZooDefs.Ids.OPEN_ACL_UNSAFE,
                    CreateMode.PERSISTENT);

            // resume poor fellow
            qu.getPeer(1).peer.resume();
        } finally {
            zlogger.removeAppender(appender);
        }

        os.close();
        LineNumberReader r = new LineNumberReader(new StringReader(os
                .toString()));
        String line;
        Pattern p = Pattern.compile(".*Majority server found.*");
        boolean found = false;
        while ((line = r.readLine()) != null) {
            if (p.matcher(line).matches()) {
                found = true;
                break;
            }
        }
        Assert.assertTrue(
                "Majority server wasn't found while connected to r/o server",
                found);
    }
}
