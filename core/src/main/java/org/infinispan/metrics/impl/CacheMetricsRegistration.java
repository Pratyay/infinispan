package org.infinispan.metrics.impl;

import java.util.Collection;
import java.util.Set;

import org.infinispan.commons.stat.MetricInfo;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.factories.KnownComponentNames;
import org.infinispan.factories.annotations.ComponentName;
import org.infinispan.factories.annotations.Inject;
import org.infinispan.factories.annotations.SurvivesRestarts;
import org.infinispan.factories.scopes.Scope;
import org.infinispan.factories.scopes.Scopes;

/**
 * Creates and registers metrics for all components from a cache's component registry.
 *
 * @author anistor@redhat.com
 * @since 10.1.3
 */
@Scope(Scopes.NAMED_CACHE)
@SurvivesRestarts
public final class CacheMetricsRegistration extends AbstractMetricsRegistration {

   @Inject
   Configuration cacheConfiguration;

   @ComponentName(KnownComponentNames.CACHE_NAME)
   @Inject
   String cacheName;

   @Override
   public boolean metricsEnabled() {
      return metricsCollector != null && cacheConfiguration.statistics().enabled();
   }

   @Override
   @Deprecated(forRemoval = true, since = "16.0")
   protected String initLegacyNamePrefix() {
      String prefix = super.initLegacyNamePrefix();
      if (!globalConfig.metrics().namesAsTags()) {
         prefix += "cache_" + NameUtils.filterIllegalChars(cacheName) + '_';
      }
      return prefix;
   }

   @Override
   protected Set<Object> internalRegisterMetrics(Object instance, Collection<MetricInfo> metrics, String metricPrefix) {
      return metricsCollector.registerMetrics(instance, metrics, metricPrefix, cacheName);
   }
}
