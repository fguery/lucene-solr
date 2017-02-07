/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.handler.component;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.params.CursorMarkParams;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test for QueryComponent to set a "replica set" on each search results.
 * When a query is executed, you could pass this "replica set" to ensure your query
 * gets executed on the same replica.
 * This will ensure you don't face the "bouncing result" problem, when you don't have the same
 * number of deleted documents.
 *
 * @see QueryComponent
 */
public class DistributedQueryComponentReplicaSetTest extends SolrCloudTestCase {

  private static final String COLLECTION = "repSet";
  private static final String id = "id";

  private static final int numShards = 4;
  private static final int numReplicas = 3;
  private static final int maxShardsPerNode = 2;

  @BeforeClass
  public static void setupCluster() throws Exception {
    configureCluster((numShards*numReplicas + (maxShardsPerNode-1))/maxShardsPerNode)
        .addConfig("conf", configset("cloud-dynamic"))
        .configure();

    CollectionAdminRequest.createCollection(COLLECTION, "conf", numShards, numReplicas)
        .setMaxShardsPerNode(maxShardsPerNode)
        .process(cluster.getSolrClient());

    new UpdateRequest()
        .add(sdoc(id, "1", "text", "a", "test_sS", "21", "payload", ByteBuffer.wrap(new byte[]{0x12, 0x62, 0x15})))
        .add(sdoc(id, "2", "text", "b", "test_sS", "22", "payload", ByteBuffer.wrap(new byte[]{0x25, 0x21, 0x16})))                       //  5
        .add(sdoc(id, "3", "text", "a", "test_sS", "23", "payload", ByteBuffer.wrap(new byte[]{0x35, 0x32, 0x58})))                       //  8
        .add(sdoc(id, "4", "text", "b", "test_sS", "24", "payload", ByteBuffer.wrap(new byte[]{0x25, 0x21, 0x15})))                       //  4
        .add(sdoc(id, "5", "text", "a", "test_sS", "25", "payload", ByteBuffer.wrap(new byte[]{0x35, 0x35, 0x10, 0x00})))                 //  9
        .add(sdoc(id, "6", "text", "c", "test_sS", "26", "payload", ByteBuffer.wrap(new byte[]{0x1a, 0x2b, 0x3c, 0x00, 0x00, 0x03})))     //  3
        .add(sdoc(id, "7", "text", "c", "test_sS", "27", "payload", ByteBuffer.wrap(new byte[]{0x00, 0x3c, 0x73})))                       //  1
        .add(sdoc(id, "8", "text", "c", "test_sS", "28", "payload", ByteBuffer.wrap(new byte[]{0x59, 0x2d, 0x4d})))                       // 11
        .add(sdoc(id, "9", "text", "a", "test_sS", "29", "payload", ByteBuffer.wrap(new byte[]{0x39, 0x79, 0x7a})))                       // 10
        .add(sdoc(id, "10", "text", "b", "test_sS", "30", "payload", ByteBuffer.wrap(new byte[]{0x31, 0x39, 0x7c})))                      //  6
        .add(sdoc(id, "11", "text", "d", "test_sS", "31", "payload", ByteBuffer.wrap(new byte[]{(byte) 0xff, (byte) 0xaf, (byte) 0x9c}))) // 13
        .add(sdoc(id, "12", "text", "d", "test_sS", "32", "payload", ByteBuffer.wrap(new byte[]{0x34, (byte) 0xdd, 0x4d})))               //  7
        .add(sdoc(id, "13", "text", "d", "test_sS", "33", "payload", ByteBuffer.wrap(new byte[]{(byte) 0x80, 0x11, 0x33})))               // 12
        .commit(cluster.getSolrClient(), COLLECTION);

  }

  @Test
  public void testBasics() throws Exception {

    QueryResponse rsp;
    rsp = cluster.getSolrClient().query(COLLECTION,
        new SolrQuery("q", "*:*", "fl", "id,test_sS,score", "sort", "payload asc", "rows", "20"));
    assertFieldValues(rsp.getResults(), id, "7", "1", "6", "4", "2", "10", "12", "3", "5", "9", "8", "13", "11");
    assertFieldValues(rsp.getResults(), "test_sS", "27", "21", "26", "24", "22", "30", "32", "23", "25", "29", "28", "33", "31");
    rsp = cluster.getSolrClient().query(COLLECTION, new SolrQuery("q", "*:*", "fl", "id,score", "sort", "payload desc", "rows", "20"));
    assertFieldValues(rsp.getResults(), id, "11", "13", "8", "9", "5", "3", "12", "10", "2", "4", "6", "1", "7");

  }

  @Test
  public void testReplicaSetNotUsed() throws Exception {
    QueryResponse rsp;
    
    rsp = cluster.getSolrClient().query(COLLECTION,
        new SolrQuery("q", "*:*", "fl", id+",score", "sort", id+" asc", "rows", "5"));
    assertNull("unexpectedly found replica set in response: "+rsp, rsp.getUsedReplicaSet());
  }
  
  @Test
  public void testReplicaSetUsed() throws Exception {
    QueryResponse rsp;
    
    rsp = cluster.getSolrClient().query(COLLECTION,
        new SolrQuery("q", "*:*", "fl", id+",score", "sort", id+" asc", "rows", "10",
            CursorMarkParams.CURSOR_MARK_PARAM, CursorMarkParams.CURSOR_MARK_START,
//            CursorMarkParams.REPLICA_SET_PARAM, CursorMarkParams.REPLICA_SET_START
            CursorMarkParams.REPLICA_SET_PARAM, "ollisTestParam"
        ));
    System.out.println("#### Olli - Used repSet " + rsp.getUsedReplicaSet());
    assertNotNull(rsp.getUsedReplicaSet());
    assertTrue("rsp does not mention "+CursorMarkParams.REPLICA_SET_USED,
        rsp.toString().contains(CursorMarkParams.REPLICA_SET_USED));
  }


  @Test
  public void testReplicaSetUsage() throws Exception {
    QueryResponse rsp;
    QueryResponse rsp2;

    rsp = cluster.getSolrClient().query(COLLECTION,
        new SolrQuery("q", "*:*", "fl", id+",score", "sort", id+" asc", "rows", "3",
            CursorMarkParams.CURSOR_MARK_PARAM, CursorMarkParams.CURSOR_MARK_START,
            CursorMarkParams.REPLICA_SET_PARAM, CursorMarkParams.REPLICA_SET_START
        ));
    TimeUnit.SECONDS.sleep(1);
    System.out.println("#### Olli - Used repSet: " + rsp.getUsedReplicaSet());
    assertNotNull(rsp.getUsedReplicaSet());
    rsp2 = cluster.getSolrClient().query(COLLECTION,
        new SolrQuery("q", "*:*", "fl", id+",score", "sort", id+" asc", "rows", "3",
            CursorMarkParams.CURSOR_MARK_PARAM, rsp.getNextCursorMark(),
            CursorMarkParams.REPLICA_SET_PARAM, rsp.getUsedReplicaSet()
        ));
    TimeUnit.SECONDS.sleep(1);
    System.out.println("#### Olli - requested repSet: " + rsp.getUsedReplicaSet()+ "\n and used repSet: " + rsp2.getUsedReplicaSet());
    assertNotNull(rsp2.getUsedReplicaSet());
    assertTrue("repSet wasn't reused "+rsp.getUsedReplicaSet(),
        rsp.getUsedReplicaSet().equals(rsp2.getUsedReplicaSet()));
  }

}
