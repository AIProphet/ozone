/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * <p>Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.hdds.scm.ha;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.hadoop.hdds.scm.server.StorageContainerManager;
import org.apache.ratis.protocol.Message;
import org.apache.ratis.protocol.RaftGroupMemberId;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.impl.BaseStateMachine;

import org.apache.hadoop.hdds.protocol.proto.SCMRatisProtocol.RequestType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TODO.
 */
public class SCMStateMachine extends BaseStateMachine {
  private static final Logger LOG =
      LoggerFactory.getLogger(SCMStateMachine.class);

  private final StorageContainerManager scm;
  private final SCMRatisServer ratisServer;
  private final Map<RequestType, Object> handlers;


  public SCMStateMachine(final StorageContainerManager scm,
                         final SCMRatisServer ratisServer) {
    this.scm = scm;
    this.ratisServer = ratisServer;
    this.handlers = new EnumMap<>(RequestType.class);
  }

  public void registerHandler(RequestType type, Object handler) {
    handlers.put(type, handler);
  }

  @Override
  public CompletableFuture<Message> applyTransaction(
      final TransactionContext trx) {
    final CompletableFuture<Message> applyTransactionFuture =
        new CompletableFuture<>();
    try {
      final SCMRatisRequest request = SCMRatisRequest.decode(
          Message.valueOf(trx.getStateMachineLogEntry().getLogData()));
      applyTransactionFuture.complete(process(request));
    } catch (Exception ex) {
      applyTransactionFuture.completeExceptionally(ex);
    }
    return applyTransactionFuture;
  }

  private Message process(final SCMRatisRequest request)
      throws Exception {
    try {
      final Object handler = handlers.get(request.getType());

      if (handler == null) {
        throw new IOException("No handler found for request type " +
            request.getType());
      }

      final List<Class<?>> argumentTypes = new ArrayList<>();
      for(Object args : request.getArguments()) {
        argumentTypes.add(args.getClass());
      }
      final Object result = handler.getClass().getMethod(
          request.getOperation(), argumentTypes.toArray(new Class<?>[0]))
          .invoke(handler, request.getArguments());

      return SCMRatisResponse.encode(result);
    } catch (NoSuchMethodException | SecurityException ex) {
      throw new InvalidProtocolBufferException(ex.getMessage());
    } catch (InvocationTargetException e) {
      final Exception targetEx = (Exception) e.getTargetException();
      throw targetEx != null ? targetEx : e;
    }
  }

  @Override
  public void notifyNotLeader(Collection<TransactionContext> pendingEntries) {
    LOG.info("current leader SCM steps down.");
    scm.getScmContext().updateIsLeaderAndTerm(false, 0);
  }

  @Override
  public void notifyLeaderChanged(RaftGroupMemberId groupMemberId,
                                  RaftPeerId newLeaderId) {
    if (!groupMemberId.getPeerId().equals(newLeaderId)) {
      LOG.info("leader changed, yet current SCM is still follower.");
      return;
    }

    long term = scm.getScmHAManager()
        .getRatisServer()
        .getDivision()
        .getInfo()
        .getCurrentTerm();

    LOG.info("current SCM becomes leader of term {}.", term);
    scm.getScmContext().updateIsLeaderAndTerm(true, term);
  }
}
