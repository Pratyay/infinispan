package org.infinispan.statetransfer;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.infinispan.Cache;
import org.infinispan.commands.VisitableCommand;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.container.DataContainer;
import org.infinispan.container.entries.InternalCacheEntry;
import org.infinispan.context.InvocationContext;
import org.infinispan.context.impl.FlagBitSets;
import org.infinispan.distribution.LocalizedCacheTopology;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.interceptors.BaseAsyncInterceptor;
import org.infinispan.interceptors.impl.InvocationContextInterceptor;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.test.fwk.CleanupAfterMethod;
import org.infinispan.test.fwk.TestCacheManagerFactory;
import org.infinispan.transaction.LockingMode;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.transaction.lookup.EmbeddedTransactionManagerLookup;
import org.testng.annotations.Test;

/**
 * Tests that config option StateTransferConfiguration.awaitInitialTransfer() is honored correctly.
 *
 * @author anistor@redhat.com
 * @since 5.2
 */
@Test(groups = "functional", testName = "statetransfer.InitialStateTransferCompletionTest")
@CleanupAfterMethod
public class InitialStateTransferCompletionTest extends MultipleCacheManagersTest {

   private ConfigurationBuilder cacheConfigBuilder;

   @Override
   protected void createCacheManagers() throws Throwable {
      cacheConfigBuilder = getDefaultClusteredCacheConfig(CacheMode.DIST_SYNC, true, true);
      cacheConfigBuilder.transaction().transactionMode(TransactionMode.TRANSACTIONAL)
            .transactionManagerLookup(new EmbeddedTransactionManagerLookup())
            .lockingMode(LockingMode.PESSIMISTIC)
            .clustering().hash().numOwners(10)  // a number bigger than actual number of nodes will make this distributed cluster behave as if fully replicated
            .stateTransfer().fetchInMemoryState(true)
            .awaitInitialTransfer(true); // setting this to false will lead to test failure

      createCluster(cacheConfigBuilder, 2);
      waitForClusterToForm();
   }

   public void testStateTransferCompletion() throws Exception {
      final int numKeys = 100;

      // populate cache
      Cache<Object, Object> cache0 = cache(0);
      for (int i = 0; i < numKeys; i++) {
         cache0.put("k" + i, "v" + i);
      }

      final AtomicBoolean ignoreFurtherStateTransfer = new AtomicBoolean();
      final AtomicInteger transferredKeys = new AtomicInteger();

      GlobalConfigurationBuilder global = GlobalConfigurationBuilder.defaultClusteredBuilder();
      TestCacheManagerFactory.addInterceptor(global, TestCacheManagerFactory.DEFAULT_CACHE_NAME::equals,
            new CountInterceptor(ignoreFurtherStateTransfer, transferredKeys), TestCacheManagerFactory.InterceptorPosition.BEFORE, InvocationContextInterceptor.class);

      // add the third member
      log.trace("Adding new member ...");
      addClusterEnabledCacheManager(global, cacheConfigBuilder);
      Cache<String, String> cache2 = cache(2);  //this must return only when all state was received
      ignoreFurtherStateTransfer.set(true);
      log.trace("Successfully added a new member");

      // check number of transferred keys
      int actualTransferredKeys = transferredKeys.get();
      assertEquals(numKeys, actualTransferredKeys);

      // check the current topology
      LocalizedCacheTopology cacheTopology = cache2.getAdvancedCache().getDistributionManager().getCacheTopology();
      assertNull(cacheTopology.getPendingCH());
      ConsistentHash readCh = cacheTopology.getReadConsistentHash();
      assertTrue(readCh.getMembers().contains(address(2)));

      // check number of keys directly in data container
      DataContainer dc2 = cache(2).getAdvancedCache().getDataContainer();
      assertEquals(numKeys, dc2.size());

      // check the expected values of these keys
      for (int i = 0; i < numKeys; i++) {
         String key = "k" + i;
         String expectedValue = "v" + i;
         assertTrue(cacheTopology.isReadOwner(key));
         InternalCacheEntry entry = dc2.peek(key);
         assertNotNull(entry);
         assertEquals(expectedValue, entry.getValue());
      }
   }

   static class CountInterceptor extends BaseAsyncInterceptor {
      private final AtomicBoolean ignoreFurtherStateTransfer;
      private final AtomicInteger transferredKeys;

      public CountInterceptor(AtomicBoolean ignoreFurtherStateTransfer, AtomicInteger transferredKeys) {
         this.ignoreFurtherStateTransfer = ignoreFurtherStateTransfer;
         this.transferredKeys = transferredKeys;
      }

      @Override
      public Object visitCommand(InvocationContext ctx, VisitableCommand cmd) throws Throwable {
         if (cmd instanceof PutKeyValueCommand &&
             ((PutKeyValueCommand) cmd).hasAnyFlag(FlagBitSets.PUT_FOR_STATE_TRANSFER)) {
            if (ignoreFurtherStateTransfer.get()) {
               return null;
            }
            return invokeNextThenAccept(ctx, cmd, (rCtx, rCommand, rv) -> {
               transferredKeys.incrementAndGet();
            });
         }

         return invokeNext(ctx, cmd);
      }
   }
}
