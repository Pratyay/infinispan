package org.infinispan.expiration.impl;

import org.infinispan.commands.Visitor;
import org.infinispan.commands.read.AbstractDataCommand;
import org.infinispan.commons.marshall.ProtoStreamTypeIds;
import org.infinispan.context.InvocationContext;
import org.infinispan.marshall.protostream.impl.MarshallableObject;
import org.infinispan.protostream.annotations.ProtoFactory;
import org.infinispan.protostream.annotations.ProtoField;
import org.infinispan.protostream.annotations.ProtoTypeId;
import org.infinispan.remoting.transport.NodeVersion;
import org.infinispan.util.ByteString;

/**
 * This command updates a cache entry's last access timestamp. If eviction is enabled, it will also update the recency information
 * <p>
 * This command returns a Boolean that is whether this command was able to touch the value or not.
 */
@ProtoTypeId(ProtoStreamTypeIds.TOUCH_COMMAND)
public class TouchCommand extends AbstractDataCommand {

   @ProtoField(6)
   boolean touchEvenIfExpired;

   @ProtoFactory
   TouchCommand(ByteString cacheName, MarshallableObject<?> wrappedKey, long flagsWithoutRemote, int topologyId, int segment, boolean touchEvenIfExpired) {
      super(cacheName, wrappedKey, flagsWithoutRemote, topologyId, segment);
      this.touchEvenIfExpired = touchEvenIfExpired;
   }

   public TouchCommand(ByteString cacheName, Object key, int segment, long flagBitSet, boolean touchEvenIfExpired) {
      super(cacheName, key, segment, flagBitSet);
      this.touchEvenIfExpired = touchEvenIfExpired;
   }

   public boolean isTouchEvenIfExpired() {
      return touchEvenIfExpired;
   }

   @Override
   public Object acceptVisitor(InvocationContext ctx, Visitor visitor) throws Throwable {
      return visitor.visitTouchCommand(ctx, this);
   }

   @Override
   public LoadType loadType() {
      return LoadType.DONT_LOAD;
   }

   @Override
   public NodeVersion supportedSince() {
      return NodeVersion.SIXTEEN;
   }
}
