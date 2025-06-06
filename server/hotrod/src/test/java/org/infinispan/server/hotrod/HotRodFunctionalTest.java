package org.infinispan.server.hotrod;

import static org.infinispan.server.hotrod.OperationStatus.InvalidMagicOrMsgId;
import static org.infinispan.server.hotrod.OperationStatus.KeyDoesNotExist;
import static org.infinispan.server.hotrod.OperationStatus.NotExecutedWithPrevious;
import static org.infinispan.server.hotrod.OperationStatus.OperationNotExecuted;
import static org.infinispan.server.hotrod.OperationStatus.ParseError;
import static org.infinispan.server.hotrod.OperationStatus.Success;
import static org.infinispan.server.hotrod.OperationStatus.SuccessWithPrevious;
import static org.infinispan.server.hotrod.OperationStatus.UnknownOperation;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.assertHotRodEquals;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.assertKeyDoesNotExist;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.assertStatus;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.assertSuccess;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.k;
import static org.infinispan.server.hotrod.test.HotRodTestingUtil.v;
import static org.infinispan.test.TestingUtil.assertBetween;
import static org.infinispan.test.TestingUtil.generateRandomString;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.infinispan.container.entries.CacheEntry;
import org.infinispan.server.hotrod.configuration.HotRodServerConfiguration;
import org.infinispan.server.hotrod.test.HotRodClient;
import org.infinispan.server.hotrod.test.TestBulkGetKeysResponse;
import org.infinispan.server.hotrod.test.TestBulkGetResponse;
import org.infinispan.server.hotrod.test.TestErrorResponse;
import org.infinispan.server.hotrod.test.TestGetWithVersionResponse;
import org.infinispan.server.hotrod.test.TestResponse;
import org.infinispan.server.hotrod.test.TestResponseWithPrevious;
import org.infinispan.server.hotrod.test.TestSizeResponse;
import org.testng.annotations.Test;

/**
 * Hot Rod server functional test.
 *
 * @author Galder Zamarreño
 * @since 4.1
 */
@Test(groups = "functional", testName = "server.hotrod.HotRodFunctionalTest")
public class HotRodFunctionalTest extends HotRodSingleNodeTest {

   public void testUnknownCommand(Method m) {
      OperationStatus status = client().execute(0xA0, (byte)255, cacheName, k(m), 0, 0, v(m), 0, (byte) 1, 0).status;
      assertEquals("Status should have been 'UnknownOperation' but instead was: " + status, status, UnknownOperation);
   }

   public void testUnknownMagic(Method m) {
      client().assertPut(m); // Do a put to make sure decoder gets back to reading properly
      OperationStatus status = client().executeExpectBadMagic(0x66, (byte) 0x01, cacheName, k(m), 0, 0, v(m), 0).status;
      assertEquals("Status should have been 'InvalidMagicOrMsgId' but instead was: " + status, status, InvalidMagicOrMsgId);
   }

   // todo: test other error conditions such as invalid version...etc

   public void testPutBasic(Method m) {
      client().assertPut(m);
   }

   public void testPutOnDefaultCache(Method m) {
      String defaultName = "";
      // Need to ping the cache first like a normal client, which ensures it is started
      assertStatus(client().ping(defaultName), Success);
      TestResponse resp = client().execute(0xA0, (byte) 0x01, defaultName, k(m), 0, 0, v(m), 0, (byte) 1, 0);
      assertStatus(resp, Success);
      assertHotRodEquals(cacheManager, k(m), v(m));
   }

   public void testPutOnUndefinedCache(Method m) {
      TestErrorResponse resp =
            ((TestErrorResponse) client().execute(0xA0, (byte) 0x01, "boomooo", k(m), 0, 0, v(m), 0, (byte) 1, 0));
      assertTrue(resp.msg.contains("CacheNotFoundException"));
      assertEquals("Status should have been 'ParseError' but instead was: " + resp.status, resp.status, ParseError);
      client().assertPut(m);
   }

   public void testPutOnTopologyCache(Method m) {
      TestErrorResponse resp = ((TestErrorResponse) client()
            .execute(0xA0, (byte) 0x01, HotRodServerConfiguration.TOPOLOGY_CACHE_NAME_PREFIX, k(m), 0, 0, v(m), 0,
                     (byte) 1, 0));
      assertTrue(resp.msg.contains("CacheNotFoundException"));
      assertEquals("Status should have been 'ParseError' but instead was: " + resp.status, resp.status, ParseError);
      client().assertPut(m);
   }

   public void testPutWithLifespan(Method m) throws InterruptedException {
      client().assertPut(m, 1, 0);
      assertEntryExpiration(k(m), 1000, -1);
   }

   public void testPutWithMaxIdle(Method m) throws InterruptedException {
      client().assertPut(m, 0, 1);
      assertEntryExpiration(k(m), -1, 1000);
   }

   public void testPutWithPreviousValue(Method m) {
      TestResponseWithPrevious resp = ((TestResponseWithPrevious) client().put(k(m), 0, 0, v(m), 1));
      assertSuccessPrevious(resp, null);
      resp = (TestResponseWithPrevious) client().put(k(m), 0, 0, v(m, "v2-"), 1);
      assertSuccessPrevious(resp, v(m));
   }

   public void testGetBasic(Method m) {
      client().assertPut(m);
      assertSuccess(client().assertGet(m), v(m));
   }

   public void testGetDoesNotExist(Method m) {
      assertKeyDoesNotExist(client().assertGet(m));
   }

   public void testPutIfAbsentNotExist(Method m) {
      TestResponse resp = client().putIfAbsent(k(m), 0, 0, v(m));
      assertStatus(resp, Success);
   }

   public void testPutIfAbsentExist(Method m) {
      client().assertPut(m);
      TestResponse resp = client().putIfAbsent(k(m), 0, 0, v(m, "v2-"));
      assertStatus(resp, OperationNotExecuted);
   }

   public void testPutIfAbsentWithLifespan(Method m) throws InterruptedException {
      TestResponse resp = client().putIfAbsent(k(m), 1, 0, v(m));
      assertStatus(resp, Success);
      assertEntryExpiration(k(m), 1000, -1);
   }

   public void testPutIfAbsentWithMaxIdle(Method m) throws InterruptedException {
      TestResponse resp = client().putIfAbsent(k(m), 0, 1, v(m));
      assertStatus(resp, Success);
      assertEntryExpiration(k(m), -1, 1000);
   }

   public void testPutIfAbsentWithPreviousValue(Method m) {
      TestResponse resp1 = client().putIfAbsent(k(m), 0, 0, v(m), 0);
      assertStatus(resp1, Success);
      TestResponseWithPrevious resp2 = (TestResponseWithPrevious) client().putIfAbsent(k(m), 0, 0, v(m, "v2-"), 1);
      assertNotExecutedPrevious(resp2, v(m));
   }

   public void testReplaceBasic(Method m) {
      client().assertPut(m);
      TestResponse resp = client().replace(k(m), 0, 0, v(m, "v1-"));
      assertStatus(resp, Success);
      assertSuccess(client().assertGet(m), v(m, "v1-"));
   }

   public void testNotReplaceIfNotPresent(Method m) {
      TestResponse resp = client().replace(k(m), 0, 0, v(m));
      assertStatus(resp, OperationNotExecuted);
   }

   public void testReplaceWithLifespan(Method m) throws InterruptedException {
      client().assertPut(m);
      TestResponse resp = client().replace(k(m), 1, 0, v(m, "v1-"));
      assertStatus(resp, Success);
      assertEntryExpiration(k(m), 1000, -1);
   }

   public void testReplaceWithMaxIdle(Method m) throws InterruptedException {
      client().assertPut(m);
      TestResponse resp = client().replace(k(m), 0, 1, v(m, "v1-"));
      assertStatus(resp, Success);
      assertEntryExpiration(k(m), -1, 1000);
   }

   public void testReplaceWithPreviousValue(Method m) {
      TestResponse resp = client().replace(k(m), 0, 0, v(m), 0);
      assertStatus(resp, OperationNotExecuted);
      TestResponseWithPrevious resp2 = (TestResponseWithPrevious) client().put(k(m), 0, 0, v(m, "v2-"), 1);
      assertSuccessPrevious(resp2, null);
      TestResponseWithPrevious resp3 = (TestResponseWithPrevious) client().replace(k(m), 0, 0, v(m, "v3-"), 1);
      assertSuccessPrevious(resp3, v(m, "v2-"));
   }

   public void testGetWithVersionBasic(Method m) {
      client().assertPut(m);
      assertSuccess(client().getWithVersion(k(m), 0), v(m), 0);
   }

   public void testGetWithVersionDoesNotExist(Method m) {
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertKeyDoesNotExist(resp);
      assertTrue(resp.dataVersion == 0);
   }

   public void testGetWithMetadata(Method m) {
      client().assertPut(m);
      assertSuccess(client().assertGet(m), v(m));
      assertSuccess(client().getWithMetadata(k(m), 0), v(m), -1, -1);
      client().remove(k(m));
      client().assertPut(m, 10, 5);
      assertSuccess(client().getWithMetadata(k(m), 0), v(m), 10, 5);
   }

   public void testReplaceIfUnmodifiedBasic(Method m) {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      TestResponse resp2 = client().replaceIfUnmodified(k(m), 0, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, Success);
   }

   public void testReplaceIfUnmodifiedNotFound(Method m) {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      TestResponse resp2 = client().replaceIfUnmodified(k(m, "k1-"), 0, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, KeyDoesNotExist);
   }

   public void testReplaceIfUnmodifiedNotExecuted(Method m) {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      TestResponse resp2 = client().replaceIfUnmodified(k(m), 0, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, Success);
      TestGetWithVersionResponse resp3 = client().getWithVersion(k(m), 0);
      assertSuccess(resp3, v(m, "v1-"), 0);
      assertTrue(resp.dataVersion != resp3.dataVersion);
      TestResponse resp4 = client().replaceIfUnmodified(k(m), 0, 0, v(m, "v2-"), resp.dataVersion);
      assertStatus(resp4, OperationNotExecuted);
      TestResponse resp5 = client().replaceIfUnmodified(k(m), 0, 0, v(m, "v2-"), resp3.dataVersion);
      assertStatus(resp5, Success);
   }

   public void testReplaceIfUnmodifiedWithPreviousValue(Method m) {
      TestResponse resp = client().replaceIfUnmodified(k(m), 0, 0, v(m), 999, 0);
      assertStatus(resp, KeyDoesNotExist);
      client().assertPut(m);
      TestGetWithVersionResponse getResp = client().getWithVersion(k(m), 0);
      assertSuccess(getResp, v(m), 0);
      TestResponseWithPrevious resp2 =
            (TestResponseWithPrevious) client().replaceIfUnmodified(k(m), 0, 0, v(m, "v2-"), 888, 1);
      assertNotExecutedPrevious(resp2, v(m));
      TestResponseWithPrevious resp3 =
            (TestResponseWithPrevious) client().replaceIfUnmodified(k(m), 0, 0, v(m, "v3-"), getResp.dataVersion, 1);
      assertSuccessPrevious(resp3, v(m));
   }

   public void testReplaceIfUnmodifiedWithExpiry(Method m) throws InterruptedException {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      assertTrue(resp.dataVersion != 0);

      int lifespanSecs = 2;
      long lifespan = TimeUnit.SECONDS.toMillis(lifespanSecs);
      long startTime = System.currentTimeMillis();
      TestResponse resp2 = client().replaceIfUnmodified(k(m), lifespanSecs, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, Success);

      assertEntryExpiration(k(m), lifespan, -1);
   }

   private void assertEntryExpiration(byte[] key, long lifespan, int maxIdle) {
      CacheEntry<byte[], byte[]> entry = advancedCache.withStorageMediaType().getCacheEntry(key);
      assertEquals(lifespan, entry.getLifespan());
      assertEquals(maxIdle, entry.getMaxIdle());
   }

   public void testRemoveBasic(Method m) {
      client().assertPut(m);
      TestResponse resp = client().remove(k(m));
      assertStatus(resp, Success);
      assertKeyDoesNotExist(client().assertGet(m));
   }

   public void testRemoveDoesNotExist(Method m) {
      assertStatus(client().remove(k(m)), KeyDoesNotExist);
   }

   public void testRemoveWithPreviousValue(Method m) {
      TestResponse resp = client().remove(k(m), 0);
      assertStatus(resp, KeyDoesNotExist);
      client().assertPut(m);
      TestResponseWithPrevious resp2 = (TestResponseWithPrevious) client().remove(k(m), 1);
      assertSuccessPrevious(resp2, v(m));
   }

   public void testRemoveIfUnmodifiedBasic(Method m) {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      assertTrue(resp.dataVersion != 0);
      TestResponse resp2 = client().removeIfUnmodified(k(m), 0, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, Success);
      assertKeyDoesNotExist(client().assertGet(m));
   }

   public void testRemoveIfUnmodifiedNotFound(Method m) {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      TestResponse resp2 = client().removeIfUnmodified(k(m, "k1-"), 0, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, KeyDoesNotExist);
      assertSuccess(client().assertGet(m), v(m));
   }

   public void testRemoveIfUnmodifiedNotExecuted(Method m) {
      client().assertPut(m);
      TestGetWithVersionResponse resp = client().getWithVersion(k(m), 0);
      assertSuccess(resp, v(m), 0);
      TestResponse resp2 = client().replaceIfUnmodified(k(m), 0, 0, v(m, "v1-"), resp.dataVersion);
      assertStatus(resp2, Success);
      TestGetWithVersionResponse resp3 = client().getWithVersion(k(m), 0);
      assertSuccess(resp3, v(m, "v1-"), 0);
      assertTrue(resp.dataVersion != resp3.dataVersion);
      TestResponse resp4 = client().removeIfUnmodified(k(m), 0, 0, v(m, "v2-"), resp.dataVersion);
      assertStatus(resp4, OperationNotExecuted);
      TestResponse resp5 = client().removeIfUnmodified(k(m), 0, 0, v(m, "v2-"), resp3.dataVersion);
      assertStatus(resp5, Success);
   }

   public void testRemoveIfUmodifiedWithPreviousValue(Method m) {
      TestResponse resp = client().removeIfUnmodified(k(m), 999, 0);
      assertStatus(resp, KeyDoesNotExist);
      client().assertPut(m);
      TestGetWithVersionResponse getResp = client().getWithVersion(k(m), 0);
      assertSuccess(getResp, v(m), 0);
      TestResponseWithPrevious resp2 = (TestResponseWithPrevious) client().removeIfUnmodified(k(m), 888, 1);
      assertNotExecutedPrevious(resp2, v(m));
      TestResponseWithPrevious resp3 =
            (TestResponseWithPrevious) client().removeIfUnmodified(k(m), getResp.dataVersion, 1);
      assertSuccessPrevious(resp3, v(m));
   }

   public void testContainsKeyBasic(Method m) {
      client().assertPut(m);
      assertStatus(client().containsKey(k(m), 0), Success);
   }

   public void testContainsKeyDoesNotExist(Method m) {
      assertStatus(client().containsKey(k(m), 0), KeyDoesNotExist);
   }

   public void testClear(Method m) {
      for (int i = 1; i <= 5; i++) {
         byte[] key = k(m, "k" + i + "-");
         byte[] value = v(m, "v" + i + "-");
         assertStatus(client().put(key, 0, 0, value), Success);
         assertStatus(client().containsKey(key, 0), Success);
      }

      assertStatus(client().clear(), Success);

      for (int i = 1; i <= 5; i++) {
         byte[] key = k(m, "k" + i + "-");
         assertStatus(client().containsKey(key, 0), KeyDoesNotExist);
      }
   }

   public void testStatsDisabled(Method m) {
      Map<String, String> s = client().stats();
      assertEquals(s.get("timeSinceStart"), "-1");
      assertEquals(s.get("currentNumberOfEntries"), "-1");
      assertEquals(s.get("stores"), "-1");
      assertEquals(s.get("retrievals"), "-1");
      assertEquals(s.get("hits"), "-1");
      assertEquals(s.get("misses"), "-1");
      assertEquals(s.get("removeHits"), "-1");
      assertEquals(s.get("removeMisses"), "-1");
   }

   public void testPing(Method m) {
      assertStatus(client().ping(), Success);
   }

   public void testPingWithTopologyAwareClient(Method m) {
      TestResponse resp = client().ping();
      assertStatus(resp, Success);
      assertEquals(resp.topologyResponse, null);
      resp = client().ping((byte) 1, 0);
      assertStatus(resp, Success);
      assertEquals(resp.topologyResponse, null);
      resp = client().ping((byte) 2, 0);
      assertStatus(resp, Success);
      assertEquals(resp.topologyResponse, null);
      resp = client().ping((byte) 3, 0);
      assertStatus(resp, Success);
      assertEquals(resp.topologyResponse, null);
   }

   public void testBulkGet(Method m) {
      int size = 100;
      byte[][] keys = new byte[size][];
      for (int i = 0; i < size; i++) {
         keys[i] = k(m, i + "k-");
         TestResponse resp = client().put(keys[i], 0, 0, v(m, i + "v-"));
         assertStatus(resp, Success);
      }
      TestBulkGetResponse resp = client().bulkGet();
      assertStatus(resp, Success);
      Map<byte[], byte[]> bulkData = resp.bulkData;
      assertEquals(size, bulkData.size());
      for (int i = 0; i < size; i++) {
         byte[] key = keys[i];
         List<Map.Entry<byte[], byte[]>> filtered = bulkData.entrySet().stream()
                                                            .filter(entry -> Arrays.equals(entry.getKey(), key))
                                                            .collect(Collectors.toList());
         assertEquals(1, filtered.size());
      }

      size = 50;
      resp = client().bulkGet(size);
      assertStatus(resp, Success);
      bulkData = resp.bulkData;
      assertEquals(size, bulkData.size());
      for (int i = 0; i < size; i++) {
         byte[] key = keys[i];
         List<Map.Entry<byte[], byte[]>> filtered = bulkData.entrySet().stream()
                                                            .filter(entry -> Arrays.equals(entry.getKey(), key))
                                                            .collect(Collectors.toList());
         if (!filtered.isEmpty()) {
            assertEquals(filtered.get(0).getValue(), v(m, i + "v-"));
         }
      }
   }

   public void testBulkGetKeys(Method m) {
      int size = 100;
      byte[][] keys = new byte[size][];
      for (int i = 0; i < size; i++) {
         keys[i] = k(m, i + "k-");
         TestResponse resp = client().put(keys[i], 0, 0, v(m, i + "v-"));
         assertStatus(resp, Success);
      }
      TestBulkGetKeysResponse resp = client().bulkGetKeys();
      assertStatus(resp, Success);
      Set<byte[]> bulkData = resp.bulkData;
      assertEquals(size, bulkData.size());
      for (int i = 0; i < size; i++) {
         byte[] key = keys[i];
         List<byte[]> filtered = bulkData.stream()
                                         .filter(bytes -> Arrays.equals(bytes, key))
                                         .collect(Collectors.toList());
         assertEquals(1, filtered.size());
      }

      resp = client().bulkGetKeys(1);
      assertStatus(resp, Success);
      bulkData = resp.bulkData;
      assertEquals(size, bulkData.size());
      for (int i = 0; i < size; i++) {
         byte[] key = keys[i];
         List<byte[]> filtered = bulkData.stream()
                                         .filter(bytes -> java.util.Arrays.equals(bytes, key))
                                         .collect(Collectors.toList());
         assertEquals(1, filtered.size());
      }

      resp = client().bulkGetKeys(2);
      assertStatus(resp, Success);
      bulkData = resp.bulkData;
      assertEquals(size, bulkData.size());
      for (int i = 0; i < size; i++) {
         byte[] key = keys[i];
         List<byte[]> filtered = bulkData.stream()
                                         .filter(bytes -> Arrays.equals(bytes, key))
                                         .collect(Collectors.toList());
         assertEquals(1, filtered.size());
      }
   }

   public void testPutBigSizeKey(Method m) {
      // Not really necessary, SingleByteFrameDecoderChannelInitializer forces the server to retry even for small keys
      byte[] key = generateRandomString(1024).getBytes();
      assertStatus(client().put(key, 0, 0, v(m)), Success);
   }

   public void testPutBigSizeValue(Method m) {
      // Not really necessary, SingleByteFrameDecoderChannelInitializer forces the server to retry even for small keys
      byte[] value = generateRandomString(1024).getBytes();
      assertStatus(client().put(k(m), 0, 0, value), Success);
   }

   public void testSize(Method m) {
      TestSizeResponse sizeStart = client().size();
      assertStatus(sizeStart, Success);
      assertEquals(0, sizeStart.size);
      for (int i = 0; i < 20; i++) {
         client().assertPut(m, "k-" + i, "v-" + i);
      }
      TestSizeResponse sizeEnd = client().size();
      assertStatus(sizeEnd, Success);
      assertEquals(20, sizeEnd.size);
   }

   protected boolean assertSuccessPrevious(TestResponseWithPrevious resp, byte[] expected) {
      if (expected == null)
         assertEquals(Optional.empty(), resp.previous);
      else
         assertTrue(java.util.Arrays.equals(expected, resp.previous.get()));
      return assertStatus(resp, SuccessWithPrevious);
   }

   protected boolean assertNotExecutedPrevious(TestResponseWithPrevious resp, byte[] expected) {
      if (expected == null)
         assertEquals(Optional.empty(), resp.previous);
      else
         assertTrue(java.util.Arrays.equals(expected, resp.previous.get()));
      return assertStatus(resp, NotExecutedWithPrevious);
   }

   public void testLifespan2x(Method m) {
      try (HotRodClient client2x = connectClient(HotRodVersion.HOTROD_29.getVersion())) {
         // Expire in 1 day by setting the lifespan in seconds since the Unix epoch
         long startMillis = System.currentTimeMillis();
         long lifespanMillis = TimeUnit.MINUTES.toMillis(1);
         int expirationUnixTime = (int) ((startMillis + lifespanMillis) / 1000);
         assertStatus(client2x.put(k(m), expirationUnixTime + 1, expirationUnixTime, v(m)), Success);

         CacheEntry<byte[], byte[]> entry = advancedCache.withStorageMediaType().getCacheEntry(k(m));
         // The lifespan is set to expirationUnixTime * 1000 - <current time on the server>
         long endMillis = System.currentTimeMillis();
         long lowerBound = expirationUnixTime * 1000L - endMillis;
         long upperBound = expirationUnixTime * 1000L - startMillis;
         assertBetween(lowerBound + 1000, upperBound + 1000, entry.getLifespan());
         assertBetween(lowerBound, upperBound, entry.getMaxIdle());
      }
   }

   public void testLifespan3x(Method m) {
      try (HotRodClient client3x = connectClient(HotRodVersion.HOTROD_30.getVersion())) {
         // Set a lifespan that would be interpreted as seconds since the Unix epoch in previous versions
         int expirationSeconds = (int) TimeUnit.DAYS.toSeconds(90);
         assertStatus(client3x.put(k(m), expirationSeconds + 1, expirationSeconds, v(m)), Success);

         CacheEntry<byte[], byte[]> entry = advancedCache.withStorageMediaType().getCacheEntry(k(m));
         // The lifespan is interpreted as seconds since inserted, not since the Unix epoch
         assertEquals(expirationSeconds * 1000L + 1000, entry.getLifespan());
         assertEquals(expirationSeconds * 1000L, entry.getMaxIdle());
      }
   }
}
