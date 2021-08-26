/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.hadoop.ozone.client.rpc;

import com.google.common.cache.Cache;
import org.apache.hadoop.conf.StorageUnit;
import org.apache.hadoop.hdds.client.DefaultReplicationConfig;
import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.scm.OzoneClientConfig;
import org.apache.hadoop.hdds.scm.XceiverClientManager;
import org.apache.hadoop.hdds.scm.XceiverClientSpi;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.ozone.MiniOzoneCluster;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.client.BucketArgs;
import org.apache.hadoop.ozone.client.ObjectStore;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneClient;
import org.apache.hadoop.ozone.client.OzoneClientFactory;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.ozone.client.io.BlockOutputStreamEntry;
import org.apache.hadoop.ozone.client.io.ECKeyOutputStream;
import org.apache.hadoop.ozone.client.io.KeyOutputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.container.TestHelper;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.HDDS_SCM_WATCHER_TIMEOUT;
import static org.apache.hadoop.hdds.scm.ScmConfigKeys.OZONE_SCM_STALENODE_INTERVAL;

/**
 * Tests key output stream.
 */
public class TestECKeyOutputStream {
  private static MiniOzoneCluster cluster;
  private static OzoneConfiguration conf = new OzoneConfiguration();
  private static OzoneClient client;
  private static ObjectStore objectStore;
  private static int chunkSize;
  private static int flushSize;
  private static int maxFlushSize;
  private static int blockSize;
  private static String volumeName;
  private static String bucketName;
  private static String keyString;
  private static int dataBlocks = 3;
  private static int parityBlocks = 2;
  private static byte[][] inputChunks = new byte[dataBlocks][chunkSize];

  /**
   * Create a MiniDFSCluster for testing.
   */
  @BeforeClass
  public static void init() throws Exception {
    chunkSize = 1024;
    flushSize = 2 * chunkSize;
    maxFlushSize = 2 * flushSize;
    blockSize = 2 * maxFlushSize;

    OzoneClientConfig clientConfig = conf.getObject(OzoneClientConfig.class);
    clientConfig.setChecksumType(ContainerProtos.ChecksumType.NONE);
    clientConfig.setStreamBufferFlushDelay(false);
    conf.setFromObject(clientConfig);

    conf.setTimeDuration(HDDS_SCM_WATCHER_TIMEOUT, 1000, TimeUnit.MILLISECONDS);
    conf.setTimeDuration(OZONE_SCM_STALENODE_INTERVAL, 3, TimeUnit.SECONDS);
    conf.setQuietMode(false);
    conf.setStorageSize(OzoneConfigKeys.OZONE_SCM_BLOCK_SIZE, 4,
        StorageUnit.MB);

    cluster = MiniOzoneCluster.newBuilder(conf).setNumDatanodes(7)
        .setTotalPipelineNumLimit(10).setBlockSize(blockSize)
        .setChunkSize(chunkSize).setStreamBufferFlushSize(flushSize)
        .setStreamBufferMaxSize(maxFlushSize)
        .setStreamBufferSizeUnit(StorageUnit.BYTES).build();
    cluster.waitForClusterToBeReady();
    client = OzoneClientFactory.getRpcClient(conf);
    objectStore = client.getObjectStore();
    keyString = UUID.randomUUID().toString();
    volumeName = "testeckeyoutputstream";
    bucketName = volumeName;
    objectStore.createVolume(volumeName);
    objectStore.getVolume(volumeName).createBucket(bucketName);
    initInputChunks();
  }

  /**
   * Shutdown MiniDFSCluster.
   */
  @AfterClass
  public static void shutdown() {
    if (cluster != null) {
      cluster.shutdown();
    }
  }

  @Test
  public void testCreateKeyWithECReplicationConfig() throws Exception {
    try (OzoneOutputStream key = TestHelper
        .createKey(keyString, new ECReplicationConfig(3, 2), 2000, objectStore,
            volumeName, bucketName)) {
      Assert.assertTrue(key.getOutputStream() instanceof ECKeyOutputStream);
    }
  }

  @Test
  public void testCreateKeyWithOutBucketDefaults() throws Exception {
    OzoneVolume volume = objectStore.getVolume(volumeName);
    OzoneBucket bucket = volume.getBucket(bucketName);
    try (OzoneOutputStream out = bucket.createKey("myKey", 2000)) {
      Assert.assertTrue(out.getOutputStream() instanceof KeyOutputStream);
      for (int i = 0; i < inputChunks.length; i++) {
        out.write(inputChunks[i]);
      }
    }
  }

  @Test
  public void testCreateKeyWithBucketDefaults() throws Exception {
    String myBucket = UUID.randomUUID().toString();
    OzoneVolume volume = objectStore.getVolume(volumeName);
    final BucketArgs.Builder bucketArgs = BucketArgs.newBuilder();
    bucketArgs.setDefaultReplicationConfig(
        new DefaultReplicationConfig(ReplicationType.EC,
            new ECReplicationConfig(3, 2)));

    volume.createBucket(myBucket, bucketArgs.build());
    OzoneBucket bucket = volume.getBucket(myBucket);

    try (OzoneOutputStream out = bucket.createKey(keyString, 2000)) {
      Assert.assertTrue(out.getOutputStream() instanceof ECKeyOutputStream);
      for (int i = 0; i < inputChunks.length; i++) {
        out.write(inputChunks[i]);
      }
    }
  }

  @Test
  public void testCreateRatisKeyAndWithECBucketDefaults() throws Exception {
    String myBucket = UUID.randomUUID().toString();
    OzoneVolume volume = objectStore.getVolume(volumeName);
    final BucketArgs.Builder bucketArgs = BucketArgs.newBuilder();
    bucketArgs.setDefaultReplicationConfig(
        new DefaultReplicationConfig(ReplicationType.EC,
            new ECReplicationConfig(3, 2)));

    volume.createBucket(myBucket, bucketArgs.build());
    OzoneBucket bucket = volume.getBucket(myBucket);
    try (OzoneOutputStream out = bucket
        .createKey("testCreateRatisKeyAndWithECBucketDefaults", 2000,
            new RatisReplicationConfig("3"), new HashMap<>())) {
      Assert.assertTrue(out.getOutputStream() instanceof KeyOutputStream);
      for (int i = 0; i < inputChunks.length; i++) {
        out.write(inputChunks[i]);
      }
    }
  }



  @Test
  public void testECKeyXceiverClientShouldNotUseCachedKeysForDifferentStreams()
      throws Exception {
    int data = 3;
    int parity = 2;
    try (OzoneOutputStream key = TestHelper
        .createKey(keyString, new ECReplicationConfig(data, parity), 1024,
            objectStore, volumeName, bucketName)) {
      final List<BlockOutputStreamEntry> streamEntries =
          ((ECKeyOutputStream) key.getOutputStream()).getStreamEntries();
      Assert.assertEquals(data + parity, streamEntries.size());
      final Cache<String, XceiverClientSpi> clientCache =
          ((XceiverClientManager) ((ECKeyOutputStream) key.getOutputStream())
              .getXceiverClientFactory()).getClientCache();
      clientCache.invalidateAll();
      clientCache.cleanUp();
      final Pipeline firstStreamPipeline = streamEntries.get(0).getPipeline();
      XceiverClientSpi xceiverClientSpi =
          ((ECKeyOutputStream) key.getOutputStream()).getXceiverClientFactory()
              .acquireClient(firstStreamPipeline);
      Assert.assertNotNull(xceiverClientSpi);
      final String firstCacheKey =
          clientCache.asMap().entrySet().iterator().next().getKey();
      List<String> prevVisitedKeys = new ArrayList<>();
      prevVisitedKeys.add(firstCacheKey);
      // Lets look at all underlying EC Block group streams and make sure
      // xceiver client entry is not repeating for all.
      for (int i = 1; i < streamEntries.size(); i++) {
        Pipeline pipeline = streamEntries.get(i).getPipeline();
        Assert.assertEquals(i, clientCache.asMap().size());
        xceiverClientSpi = ((ECKeyOutputStream) key.getOutputStream())
            .getXceiverClientFactory().acquireClient(pipeline);
        Assert.assertNotNull(xceiverClientSpi);
        Assert.assertEquals(i + 1, clientCache.asMap().size());
        final String newCacheKey =
            getNewKey(clientCache.asMap().entrySet().iterator(),
                prevVisitedKeys);
        prevVisitedKeys.add(newCacheKey);
        Assert.assertNotEquals(firstCacheKey, newCacheKey);
      }
    }
  }

  private String getNewKey(
      Iterator<Map.Entry<String, XceiverClientSpi>> iterator,
      List<String> prevVisitedKeys) {
    while (iterator.hasNext()) {
      final String key = iterator.next().getKey();
      if (!prevVisitedKeys.contains(key)) {
        return key;
      }
    }
    return null;
  }

  private static void initInputChunks() {
    for (int i = 0; i < dataBlocks; i++) {
      inputChunks[i] = getBytesWith(i + 1, chunkSize);
    }
  }

  private static byte[] getBytesWith(int singleDigitNumber, int total) {
    StringBuilder builder = new StringBuilder(singleDigitNumber);
    for (int i = 1; i <= total; i++) {
      builder.append(singleDigitNumber);
    }
    return builder.toString().getBytes(UTF_8);
  }
}
