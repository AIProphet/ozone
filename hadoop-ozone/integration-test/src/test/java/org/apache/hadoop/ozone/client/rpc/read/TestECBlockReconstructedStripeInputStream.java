/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.ozone.client.rpc.read;

import org.apache.hadoop.hdds.client.ECReplicationConfig;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.io.ByteBufferPool;
import org.apache.hadoop.io.ElasticByteBufferPool;
import org.apache.hadoop.ozone.client.io.ECBlockInputStream;
import org.apache.hadoop.ozone.client.io.ECBlockReconstructedStripeInputStream;
import org.apache.hadoop.ozone.client.io.InsufficientLocationsException;
import org.apache.hadoop.ozone.client.rpc.read.ECStreamTestUtil.TestBlockInputStreamFactory;
import org.apache.hadoop.ozone.client.rpc.read.ECStreamTestUtil.TestBlockInputStream;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

import static org.apache.hadoop.ozone.client.rpc.read.ECStreamTestUtil.generateParity;

/**
 * Test for the ECBlockReconstructedStripeInputStream.
 */
public class TestECBlockReconstructedStripeInputStream {

  private static final int ONEMB = 1024 * 1024;

  private ECReplicationConfig repConfig;
  private ECStreamTestUtil.TestBlockInputStreamFactory streamFactory;
  private long randomSeed;
  private ThreadLocalRandom random = ThreadLocalRandom.current();
  private SplittableRandom dataGen;
  private ByteBufferPool bufferPool = new ElasticByteBufferPool();

  @Before
  public void setup() {
    repConfig = new ECReplicationConfig(3, 2,
        ECReplicationConfig.EcCodec.RS, ONEMB);
    streamFactory = new ECStreamTestUtil.TestBlockInputStreamFactory();

    randomSeed = random.nextLong();
    dataGen = new SplittableRandom(randomSeed);
  }

  @Test
  public void testSufficientLocations() throws IOException {
    // One chunk, only 1 location.
    OmKeyLocationInfo keyInfo = ECStreamTestUtil
        .createKeyInfo(repConfig, 1, ONEMB);
    try (ECBlockInputStream ecb = createInputStream(keyInfo)) {
      Assert.assertTrue(ecb.hasSufficientLocations());
    }
    // Two Chunks, but missing data block 2.
    Map<DatanodeDetails, Integer> dnMap
        = ECStreamTestUtil.createIndexMap(1, 4, 5);
    keyInfo = ECStreamTestUtil.createKeyInfo(repConfig, ONEMB * 2, dnMap);
    try (ECBlockInputStream ecb = createInputStream(keyInfo)) {
      Assert.assertTrue(ecb.hasSufficientLocations());
    }

    // Three Chunks, but missing data block 2 and 3.
    dnMap = ECStreamTestUtil.createIndexMap(1, 4, 5);
    keyInfo = ECStreamTestUtil.createKeyInfo(repConfig, ONEMB * 3, dnMap);
    try (ECBlockInputStream ecb =  createInputStream(keyInfo)) {
      Assert.assertTrue(ecb.hasSufficientLocations());
      // Set a failed location
      List<DatanodeDetails> failed = new ArrayList<>();
      failed.add(keyInfo.getPipeline().getFirstNode());
      ((ECBlockReconstructedStripeInputStream)ecb).addFailedDatanodes(failed);
      Assert.assertFalse(ecb.hasSufficientLocations());
    }

    // Three Chunks, but missing data block 2 and 3 and parity 1.
    dnMap = ECStreamTestUtil.createIndexMap(1, 4);
    keyInfo = ECStreamTestUtil.createKeyInfo(repConfig, ONEMB * 3, dnMap);
    try (ECBlockInputStream ecb = createInputStream(keyInfo)) {
      Assert.assertFalse(ecb.hasSufficientLocations());
    }

    // Three Chunks, all available but fail 3
    dnMap = ECStreamTestUtil.createIndexMap(1, 2, 3, 4, 5);
    keyInfo = ECStreamTestUtil.createKeyInfo(repConfig, ONEMB * 3, dnMap);
    try (ECBlockInputStream ecb = createInputStream(keyInfo)) {
      Assert.assertTrue(ecb.hasSufficientLocations());
      // Set a failed location
      List<DatanodeDetails> failed = new ArrayList<>();
      for (DatanodeDetails dn : dnMap.keySet()) {
        failed.add(dn);
        if (failed.size() == 3) {
          break;
        }
      }
      ((ECBlockReconstructedStripeInputStream)ecb).addFailedDatanodes(failed);
      Assert.assertFalse(ecb.hasSufficientLocations());
    }

    // One chunk, indexes 2 and 3 are padding, but still reported in the
    // container list. The other locations are missing so we should have
    // insufficient locations.
    dnMap = ECStreamTestUtil.createIndexMap(2, 3);
    keyInfo = ECStreamTestUtil.createKeyInfo(repConfig, ONEMB, dnMap);
    try (ECBlockInputStream ecb = createInputStream(keyInfo)) {
      Assert.assertFalse(ecb.hasSufficientLocations());
    }
  }

  @Test
  public void testReadFullStripesWithPartial() throws IOException {
    // Generate the input data for 3 full stripes and generate the parity.
    int chunkSize = repConfig.getEcChunkSize();
    int partialStripeSize = chunkSize * 2 - 1;
    int blockLength = chunkSize * repConfig.getData() * 3 + partialStripeSize;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 4 * chunkSize);
    ECStreamTestUtil.randomFill(dataBufs, chunkSize, dataGen, blockLength);

    ByteBuffer[] parity = generateParity(dataBufs, repConfig);

    List<Map<DatanodeDetails, Integer>> locations = new ArrayList<>();
    // Two data missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 4, 5));
    // One data missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 4, 5));
    // Two data missing including first
    locations.add(ECStreamTestUtil.createIndexMap(2, 4, 5));
    // One data and one parity missing
    locations.add(ECStreamTestUtil.createIndexMap(2, 3, 4));
    // No missing indexes
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 3, 4, 5));

    for (Map<DatanodeDetails, Integer> dnMap : locations) {
      streamFactory = new TestBlockInputStreamFactory();
      addDataStreamsToFactory(dataBufs, parity);

      OmKeyLocationInfo keyInfo = ECStreamTestUtil.createKeyInfo(repConfig,
          stripeSize() * 3 + partialStripeSize, dnMap);
      streamFactory.setCurrentPipeline(keyInfo.getPipeline());

      ByteBuffer[] bufs = allocateByteBuffers(repConfig);
      dataGen = new SplittableRandom(randomSeed);
      try (ECBlockReconstructedStripeInputStream ecb =
          createInputStream(keyInfo)) {
        // Read 3 full stripes
        for (int i = 0; i < 3; i++) {
          int read = ecb.readStripe(bufs);
          for (int j = 0; j < bufs.length; j++) {
            ECStreamTestUtil.assertBufferMatches(bufs[j], dataGen);
          }
          Assert.assertEquals(stripeSize(), read);

          // Check the underlying streams have read 1 chunk per read:
          for (TestBlockInputStream bis : streamFactory.getBlockStreams()) {
            Assert.assertEquals(chunkSize * (i + 1),
                bis.getPos());
          }
          Assert.assertEquals(stripeSize() * (i + 1), ecb.getPos());
          clearBuffers(bufs);
        }
        // The next read is a partial stripe
        int read = ecb.readStripe(bufs);
        Assert.assertEquals(partialStripeSize, read);
        ECStreamTestUtil.assertBufferMatches(bufs[0], dataGen);
        ECStreamTestUtil.assertBufferMatches(bufs[1], dataGen);
        Assert.assertEquals(0, bufs[2].remaining());
        Assert.assertEquals(0, bufs[2].position());

        // A further read should give EOF
        clearBuffers(bufs);
        read = ecb.readStripe(bufs);
        Assert.assertEquals(-1, read);
      }
    }
  }

  @Test
  public void testReadPartialStripe() throws IOException {
    int blockLength = repConfig.getEcChunkSize() - 1;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 3 * ONEMB);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);
    addDataStreamsToFactory(dataBufs, parity);

    ByteBuffer[] bufs = allocateByteBuffers(repConfig);
    // We have a length that is less than a single chunk, so blocks 2 and 3
    // are padding and will not be present. Block 1 is lost and needs recovered
    // from the parity and padded blocks 2 and 3.
    Map<DatanodeDetails, Integer> dnMap =
        ECStreamTestUtil.createIndexMap(4, 5);
    OmKeyLocationInfo keyInfo =
        ECStreamTestUtil.createKeyInfo(repConfig, blockLength, dnMap);
    streamFactory.setCurrentPipeline(keyInfo.getPipeline());
    dataGen = new SplittableRandom(randomSeed);
    try (ECBlockReconstructedStripeInputStream ecb =
        createInputStream(keyInfo)) {
      int read = ecb.readStripe(bufs);
      Assert.assertEquals(blockLength, read);
      ECStreamTestUtil.assertBufferMatches(bufs[0], dataGen);
      Assert.assertEquals(0, bufs[1].remaining());
      Assert.assertEquals(0, bufs[1].position());
      Assert.assertEquals(0, bufs[2].remaining());
      Assert.assertEquals(0, bufs[2].position());
      // Check the underlying streams have been advanced by 1 blockLength:
      for (TestBlockInputStream bis : streamFactory.getBlockStreams()) {
        Assert.assertEquals(blockLength, bis.getPos());
      }
      Assert.assertEquals(ecb.getPos(), blockLength);
      clearBuffers(bufs);
      // A further read should give EOF
      read = ecb.readStripe(bufs);
      Assert.assertEquals(-1, read);
    }
  }

  @Test
  public void testReadPartialStripeTwoChunks() throws IOException {
    int chunkSize = repConfig.getEcChunkSize();
    int blockLength = chunkSize * 2 - 1;

    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 3 * ONEMB);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);
    addDataStreamsToFactory(dataBufs, parity);

    ByteBuffer[] bufs = allocateByteBuffers(repConfig);
    // We have a length that is less than a single chunk, so blocks 2 and 3
    // are padding and will not be present. Block 1 is lost and needs recovered
    // from the parity and padded blocks 2 and 3.
    Map<DatanodeDetails, Integer> dnMap =
        ECStreamTestUtil.createIndexMap(4, 5);
    OmKeyLocationInfo keyInfo =
        ECStreamTestUtil.createKeyInfo(repConfig, blockLength, dnMap);
    streamFactory.setCurrentPipeline(keyInfo.getPipeline());
    dataGen = new SplittableRandom(randomSeed);
    try (ECBlockReconstructedStripeInputStream ecb =
        createInputStream(keyInfo)) {
      int read = ecb.readStripe(bufs);
      Assert.assertEquals(blockLength, read);
      ECStreamTestUtil.assertBufferMatches(bufs[0], dataGen);
      ECStreamTestUtil.assertBufferMatches(bufs[1], dataGen);
      Assert.assertEquals(0, bufs[2].remaining());
      Assert.assertEquals(0, bufs[2].position());
      // Check the underlying streams have been advanced by 1 chunk:
      for (TestBlockInputStream bis : streamFactory.getBlockStreams()) {
        Assert.assertEquals(chunkSize, bis.getPos());
      }
      Assert.assertEquals(ecb.getPos(), blockLength);
      clearBuffers(bufs);
      // A further read should give EOF
      read = ecb.readStripe(bufs);
      Assert.assertEquals(-1, read);
    }
  }

  @Test
  public void testReadPartialStripeThreeChunks() throws IOException {
    int chunkSize = repConfig.getEcChunkSize();
    int blockLength = chunkSize * 3 - 1;

    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 3 * ONEMB);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);

    // We have a length that is less than a stripe, so chunks 1 and 2 are full.
    // Block 1 is lost and needs recovered
    // from the parity and padded blocks 2 and 3.

    List<Map<DatanodeDetails, Integer>> locations = new ArrayList<>();
    // Two data missing
    locations.add(ECStreamTestUtil.createIndexMap(3, 4, 5));
    // Two data missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 4, 5));
    // One data missing - the last one
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 5));
    // One data and one parity missing
    locations.add(ECStreamTestUtil.createIndexMap(2, 3, 4));
    // One data and one parity missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 4));
    // No indexes missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 3, 4, 5));

    for (Map<DatanodeDetails, Integer> dnMap : locations) {
      streamFactory = new TestBlockInputStreamFactory();
      addDataStreamsToFactory(dataBufs, parity);
      ByteBuffer[] bufs = allocateByteBuffers(repConfig);

      OmKeyLocationInfo keyInfo =
          ECStreamTestUtil.createKeyInfo(repConfig, blockLength, dnMap);
      streamFactory.setCurrentPipeline(keyInfo.getPipeline());
      dataGen = new SplittableRandom(randomSeed);
      try (ECBlockReconstructedStripeInputStream ecb =
          createInputStream(keyInfo)) {
        int read = ecb.readStripe(bufs);
        Assert.assertEquals(blockLength, read);
        ECStreamTestUtil.assertBufferMatches(bufs[0], dataGen);
        ECStreamTestUtil.assertBufferMatches(bufs[1], dataGen);
        ECStreamTestUtil.assertBufferMatches(bufs[2], dataGen);
        // Check the underlying streams have been advanced by 1 chunk:
        for (TestBlockInputStream bis : streamFactory.getBlockStreams()) {
          Assert.assertEquals(0, bis.getRemaining());
        }
        Assert.assertEquals(ecb.getPos(), blockLength);
        clearBuffers(bufs);
        // A further read should give EOF
        read = ecb.readStripe(bufs);
        Assert.assertEquals(-1, read);
      }
    }
  }

  @Test
  public void testErrorThrownIfBlockNotLongEnough() throws IOException {
    int blockLength = repConfig.getEcChunkSize() - 1;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 3 * ONEMB);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);
    addDataStreamsToFactory(dataBufs, parity);

    // Set the parity buffer limit to be less than the block length
    parity[0].limit(blockLength - 1);
    parity[1].limit(blockLength - 1);

    ByteBuffer[] bufs = allocateByteBuffers(repConfig);
    // We have a length that is less than a single chunk, so blocks 2 and 3
    // are padding and will not be present. Block 1 is lost and needs recovered
    // from the parity and padded blocks 2 and 3.
    Map<DatanodeDetails, Integer> dnMap =
        ECStreamTestUtil.createIndexMap(4, 5);
    OmKeyLocationInfo keyInfo =
        ECStreamTestUtil.createKeyInfo(repConfig, blockLength, dnMap);
    streamFactory.setCurrentPipeline(keyInfo.getPipeline());
    try (ECBlockReconstructedStripeInputStream ecb =
        createInputStream(keyInfo)) {
      try {
        ecb.readStripe(bufs);
        Assert.fail("Read should have thrown an exception");
      } catch (InsufficientLocationsException e) {
        // expected
      }
    }
  }

  @Test
  public void testSeek() throws IOException {
    // Generate the input data for 3 full stripes and generate the parity
    // and a partial stripe
    int chunkSize = repConfig.getEcChunkSize();
    int partialStripeSize = chunkSize * 2 - 1;
    int dataLength = stripeSize() * 3 + partialStripeSize;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 4 * chunkSize);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, dataLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);

    List<Map<DatanodeDetails, Integer>> locations = new ArrayList<>();
    // Two data missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 4, 5));
    // One data missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 4, 5));
    // Two data missing including first
    locations.add(ECStreamTestUtil.createIndexMap(2, 4, 5));
    // One data and one parity missing
    locations.add(ECStreamTestUtil.createIndexMap(2, 3, 4));
    // No locations missing
    locations.add(ECStreamTestUtil.createIndexMap(1, 2, 3, 4, 5));

    for (Map<DatanodeDetails, Integer> dnMap : locations) {
      streamFactory = new TestBlockInputStreamFactory();
      addDataStreamsToFactory(dataBufs, parity);

      OmKeyLocationInfo keyInfo = ECStreamTestUtil.createKeyInfo(repConfig,
          stripeSize() * 3 + partialStripeSize, dnMap);
      streamFactory.setCurrentPipeline(keyInfo.getPipeline());

      ByteBuffer[] bufs = allocateByteBuffers(repConfig);
      try (ECBlockReconstructedStripeInputStream ecb =
          createInputStream(keyInfo)) {

        // Read Stripe 1
        int read = ecb.readStripe(bufs);
        for (int j = 0; j < bufs.length; j++) {
          validateContents(dataBufs[j], bufs[j], 0, chunkSize);
        }
        Assert.assertEquals(stripeSize(), read);
        Assert.assertEquals(dataLength - stripeSize(), ecb.getRemaining());

        // Seek to 0 and read again
        clearBuffers(bufs);
        ecb.seek(0);
        ecb.readStripe(bufs);
        for (int j = 0; j < bufs.length; j++) {
          validateContents(dataBufs[j], bufs[j], 0, chunkSize);
        }
        Assert.assertEquals(stripeSize(), read);
        Assert.assertEquals(dataLength - stripeSize(), ecb.getRemaining());

        // Seek to the last stripe
        // Seek to the last stripe
        clearBuffers(bufs);
        ecb.seek(stripeSize() * 3);
        read = ecb.readStripe(bufs);
        validateContents(dataBufs[0], bufs[0], 3 * chunkSize, chunkSize);
        validateContents(dataBufs[1], bufs[1], 3 * chunkSize, chunkSize - 1);
        Assert.assertEquals(0, bufs[2].remaining());
        Assert.assertEquals(partialStripeSize, read);
        Assert.assertEquals(0, ecb.getRemaining());

        // seek to the start of stripe 3
        clearBuffers(bufs);
        ecb.seek(stripeSize() * (long)2);
        read = ecb.readStripe(bufs);
        for (int j = 0; j < bufs.length; j++) {
          validateContents(dataBufs[j], bufs[j], 2 * chunkSize, chunkSize);
        }
        Assert.assertEquals(stripeSize(), read);
        Assert.assertEquals(partialStripeSize, ecb.getRemaining());
      }
    }
  }

  @Test
  public void testSeekToPartialOffsetFails() {
    Map<DatanodeDetails, Integer> dnMap =
        ECStreamTestUtil.createIndexMap(1, 4, 5);
    OmKeyLocationInfo keyInfo = ECStreamTestUtil.createKeyInfo(repConfig,
        stripeSize() * 3, dnMap);
    streamFactory.setCurrentPipeline(keyInfo.getPipeline());

    try (ECBlockReconstructedStripeInputStream ecb =
        createInputStream(keyInfo)) {
      try {
        ecb.seek(10);
        Assert.fail("Seek should have thrown an exception");
      } catch (IOException e) {
        Assert.assertEquals("Requested position 10 does not align " +
            "with a stripe offset", e.getMessage());
      }
    }
  }

  @Test
  public void testErrorReadingBlockContinuesReading() throws IOException {
    // Generate the input data for 3 full stripes and generate the parity.
    int chunkSize = repConfig.getEcChunkSize();
    int partialStripeSize = chunkSize * 2 - 1;
    int blockLength = repConfig.getEcChunkSize() * repConfig.getData() * 3
        + partialStripeSize;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(),
        4 * chunkSize);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);

    List<List<Integer>> failLists = new ArrayList<>();
    failLists.add(indexesToList(0, 1));
    // These will be the first parity read and then the next parity read as a
    // replacement
    failLists.add(indexesToList(2, 3));
    // First parity and then the data block
    failLists.add(indexesToList(2, 0));

    for (List<Integer> failList : failLists) {
      streamFactory = new TestBlockInputStreamFactory();
      addDataStreamsToFactory(dataBufs, parity);

      // Data block index 3 is missing and needs recovered initially.
      Map<DatanodeDetails, Integer> dnMap =
          ECStreamTestUtil.createIndexMap(1, 2, 4, 5);
      OmKeyLocationInfo keyInfo = ECStreamTestUtil.createKeyInfo(repConfig,
          stripeSize() * 3 + partialStripeSize, dnMap);
      streamFactory.setCurrentPipeline(keyInfo.getPipeline());

      ByteBuffer[] bufs = allocateByteBuffers(repConfig);
      try (ECBlockReconstructedStripeInputStream ecb =
          createInputStream(keyInfo)) {
        // After reading the first stripe, make one of the streams error
        for (int i = 0; i < 3; i++) {
          int read = ecb.readStripe(bufs);
          for (int j = 0; j < bufs.length; j++) {
            validateContents(dataBufs[j], bufs[j], i * chunkSize, chunkSize);
          }
          Assert.assertEquals(stripeSize() * (i + 1), ecb.getPos());
          Assert.assertEquals(stripeSize(), read);
          clearBuffers(bufs);
          if (i == 0) {
            streamFactory.getBlockStreams().get(failList.remove(0))
                .setShouldError(true);
          }
        }
        // The next read is a partial stripe
        int read = ecb.readStripe(bufs);
        Assert.assertEquals(partialStripeSize, read);
        validateContents(dataBufs[0], bufs[0], 3 * chunkSize, chunkSize);
        validateContents(dataBufs[1], bufs[1], 3 * chunkSize, chunkSize - 1);
        Assert.assertEquals(0, bufs[2].remaining());
        Assert.assertEquals(0, bufs[2].position());

        // seek back to zero, make another block fail. The next read should
        // error as there are not enough blocks to read.
        ecb.seek(0);
        streamFactory.getBlockStreams().get(failList.remove(0))
            .setShouldError(true);
        try {
          clearBuffers(bufs);
          ecb.readStripe(bufs);
          Assert.fail("InsufficientLocationsException expected");
        } catch (InsufficientLocationsException e) {
          // expected
        }
      }
    }
  }

  @Test(expected = InsufficientLocationsException.class)
  public void testAllLocationsFailOnFirstRead() throws IOException {
    // This test simulates stale nodes. When the nodes are stale, but not yet
    // dead, the locations will still be given to the client and it will try to
    // read them, but the read will always fail.
    // Additionally, if the key is small (less than 2 EC chunks), the locations
    // for the indexes which are all padding will be returned to the client and
    // this can confuse the "sufficient locations" check, resulting in a strange
    // error when selecting parity indexes (HDDS-6258)
    int chunkSize = repConfig.getEcChunkSize();
    int partialStripeSize = chunkSize;
    int blockLength = partialStripeSize;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), chunkSize);
    ECStreamTestUtil
        .randomFill(dataBufs, repConfig.getEcChunkSize(), dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);

    streamFactory = new TestBlockInputStreamFactory();
    addDataStreamsToFactory(dataBufs, parity);
    // Fail all the indexes containing data on their first read.
    streamFactory.setFailIndexes(indexesToList(1, 4, 5));
    // The locations contain the padded indexes, as will often be the case
    // when containers are reported by SCM.
    Map<DatanodeDetails, Integer> dnMap =
          ECStreamTestUtil.createIndexMap(1, 2, 3, 4, 5);
    OmKeyLocationInfo keyInfo = ECStreamTestUtil.createKeyInfo(repConfig,
        blockLength, dnMap);
    streamFactory.setCurrentPipeline(keyInfo.getPipeline());

    ByteBuffer[] bufs = allocateByteBuffers(repConfig);
    try (ECBlockReconstructedStripeInputStream ecb =
             createInputStream(keyInfo)) {
      ecb.readStripe(bufs);
    }
  }

  @Test
  public void testFailedLocationsAreNotRead() throws IOException {
    // Generate the input data for 3 full stripes and generate the parity.
    int chunkSize = repConfig.getEcChunkSize();
    int partialStripeSize = chunkSize * 2 - 1;
    int blockLength = chunkSize * repConfig.getData() * 3 + partialStripeSize;
    ByteBuffer[] dataBufs = allocateBuffers(repConfig.getData(), 4 * chunkSize);
    ECStreamTestUtil.randomFill(dataBufs, chunkSize, dataGen, blockLength);
    ByteBuffer[] parity = generateParity(dataBufs, repConfig);

    streamFactory = new TestBlockInputStreamFactory();
    addDataStreamsToFactory(dataBufs, parity);

    Map<DatanodeDetails, Integer> dnMap =
        ECStreamTestUtil.createIndexMap(1, 2, 3, 4, 5);
    OmKeyLocationInfo keyInfo = ECStreamTestUtil.createKeyInfo(repConfig,
        stripeSize() * 3 + partialStripeSize, dnMap);
    streamFactory.setCurrentPipeline(keyInfo.getPipeline());

    ByteBuffer[] bufs = allocateByteBuffers(repConfig);
    dataGen = new SplittableRandom(randomSeed);
    try (ECBlockReconstructedStripeInputStream ecb =
        createInputStream(keyInfo)) {
      List<DatanodeDetails> failed = new ArrayList<>();
      // Set the first 3 DNs as failed
      for (Map.Entry<DatanodeDetails, Integer> e : dnMap.entrySet()) {
        if (e.getValue() <= 2) {
          failed.add(e.getKey());
        }
      }
      ecb.addFailedDatanodes(failed);

      // Read full stripe
      int read = ecb.readStripe(bufs);
      for (int j = 0; j < bufs.length; j++) {
        ECStreamTestUtil.assertBufferMatches(bufs[j], dataGen);
      }
      Assert.assertEquals(stripeSize(), read);

      // Now ensure that streams with repIndexes 1, 2 and 3 have not been
      // created in the stream factory, indicating we did not read them.
      List<TestBlockInputStream> streams = streamFactory.getBlockStreams();
      for (TestBlockInputStream stream : streams) {
        Assert.assertTrue(stream.getEcReplicaIndex() > 2);
      }
    }
  }

  private ECBlockReconstructedStripeInputStream createInputStream(
      OmKeyLocationInfo keyInfo) {
    return new ECBlockReconstructedStripeInputStream(repConfig, keyInfo, true,
        null, null, streamFactory, bufferPool);
  }

  private List<Integer> indexesToList(int... indexes) {
    List<Integer> list = new ArrayList<>();
    for (int i : indexes) {
      list.add(i);
    }
    return list;
  }

  private void addDataStreamsToFactory(ByteBuffer[] data, ByteBuffer[] parity) {
    List<ByteBuffer> dataStreams = new ArrayList<>();
    for (ByteBuffer b : data) {
      dataStreams.add(b);
    }
    for (ByteBuffer b : parity) {
      dataStreams.add(b);
    }
    streamFactory.setBlockStreamData(dataStreams);
  }

  /**
   * Validates that the data buffer has the same contents as the source buffer,
   * starting the checks in the src at offset and for count bytes.
   * @param src The source of the data
   * @param data The data which should be checked against the source
   * @param offset The starting point in the src buffer
   * @param count How many bytes to check.
   */
  private void validateContents(ByteBuffer src, ByteBuffer data, int offset,
      int count) {
    byte[] srcArray = src.array();
    Assert.assertEquals(count, data.remaining());
    for (int i = offset; i < offset + count; i++) {
      Assert.assertEquals("Element " + i, srcArray[i], data.get());
    }
    data.flip();
  }

  /**
   * Return a list of num ByteBuffers of the given size.
   * @param num Number of buffers to create
   * @param size The size of each buffer
   * @return
   */
  private ByteBuffer[] allocateBuffers(int num, int size) {
    ByteBuffer[] bufs = new ByteBuffer[num];
    for (int i = 0; i < num; i++) {
      bufs[i] = ByteBuffer.allocate(size);
    }
    return bufs;
  }

  private int stripeSize() {
    return stripeSize(repConfig);
  }

  private int stripeSize(ECReplicationConfig rconfig) {
    return rconfig.getEcChunkSize() * rconfig.getData();
  }

  private void clearBuffers(ByteBuffer[] bufs) {
    for (ByteBuffer b : bufs) {
      b.clear();
    }
  }

  private ByteBuffer[] allocateByteBuffers(ECReplicationConfig rConfig) {
    ByteBuffer[] bufs = new ByteBuffer[repConfig.getData()];
    for (int i = 0; i < bufs.length; i++) {
      bufs[i] = ByteBuffer.allocate(rConfig.getEcChunkSize());
    }
    return bufs;
  }

}
