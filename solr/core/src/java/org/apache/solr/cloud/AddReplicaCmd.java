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

package org.apache.solr.cloud;


import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.client.solrj.cloud.autoscaling.Policy;
import org.apache.solr.client.solrj.cloud.autoscaling.PolicyHelper;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.DocCollection;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CoreAdminParams;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.Utils;
import org.apache.solr.handler.component.ShardHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.solr.cloud.Assign.getNodesForNewReplicas;
import static org.apache.solr.cloud.OverseerCollectionMessageHandler.COLL_CONF;
import static org.apache.solr.cloud.OverseerCollectionMessageHandler.SKIP_CREATE_REPLICA_IN_CLUSTER_STATE;
import static org.apache.solr.common.cloud.ZkStateReader.COLLECTION_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.SHARD_ID_PROP;
import static org.apache.solr.common.params.CollectionParams.CollectionAction.ADDREPLICA;
import static org.apache.solr.common.params.CommonAdminParams.ASYNC;
import static org.apache.solr.common.params.CommonAdminParams.WAIT_FOR_FINAL_STATE;

public class AddReplicaCmd implements OverseerCollectionMessageHandler.Cmd {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final OverseerCollectionMessageHandler ocmh;

  public AddReplicaCmd(OverseerCollectionMessageHandler ocmh) {
    this.ocmh = ocmh;
  }

  @Override
  public void call(ClusterState state, ZkNodeProps message, NamedList results) throws Exception {
    addReplica(ocmh.zkStateReader.getClusterState(), message, results, null);
  }

  ZkNodeProps addReplica(ClusterState clusterState, ZkNodeProps message, NamedList results, Runnable onComplete)
      throws IOException, InterruptedException {
    log.debug("addReplica() : {}", Utils.toJSONString(message));
    String collection = message.getStr(COLLECTION_PROP);
    String node = message.getStr(CoreAdminParams.NODE);
    String shard = message.getStr(SHARD_ID_PROP);
    String coreName = message.getStr(CoreAdminParams.NAME);
    String coreNodeName = message.getStr(CoreAdminParams.CORE_NODE_NAME);
    int timeout = message.getInt("timeout", 10 * 60); // 10 minutes
    Replica.Type replicaType = Replica.Type.valueOf(message.getStr(ZkStateReader.REPLICA_TYPE, Replica.Type.NRT.name()).toUpperCase(Locale.ROOT));
    boolean parallel = message.getBool("parallel", false);
    boolean waitForFinalState = message.getBool(WAIT_FOR_FINAL_STATE, false);
    if (StringUtils.isBlank(coreName)) {
      coreName = message.getStr(CoreAdminParams.PROPERTY_PREFIX + CoreAdminParams.NAME);
    }

    final String asyncId = message.getStr(ASYNC);

    DocCollection coll = clusterState.getCollection(collection);
    if (coll == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Collection: " + collection + " does not exist");
    }
    if (coll.getSlice(shard) == null) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST,
          "Collection: " + collection + " shard: " + shard + " does not exist");
    }
    ShardHandler shardHandler = ocmh.shardHandlerFactory.getShardHandler();
    boolean skipCreateReplicaInClusterState = message.getBool(SKIP_CREATE_REPLICA_IN_CLUSTER_STATE, false);

    final Long policyVersionBefore = PolicyHelper.REF_VERSION.get();
    AtomicLong policyVersionAfter  = new AtomicLong(-1);
    // Kind of unnecessary, but it does put the logic of whether to override maxShardsPerNode in one place.
    if (!skipCreateReplicaInClusterState) {
      if (CreateShardCmd.usePolicyFramework(coll, ocmh)) {
        if (node == null) {
          if(coll.getPolicyName() != null) message.getProperties().put(Policy.POLICY, coll.getPolicyName());
          node = Assign.identifyNodes(ocmh,
              clusterState,
              Collections.emptyList(),
              collection,
              message,
              Collections.singletonList(shard),
              replicaType == Replica.Type.NRT ? 0 : 1,
              replicaType == Replica.Type.TLOG ? 0 : 1,
              replicaType == Replica.Type.PULL ? 0 : 1
          ).get(0).node;
          if (policyVersionBefore == null && PolicyHelper.REF_VERSION.get() != null) {
            policyVersionAfter.set(PolicyHelper.REF_VERSION.get());
          }
        }
      } else {
        node = getNodesForNewReplicas(clusterState, collection, shard, 1, node,
            ocmh.overseer.getSolrCloudManager(), ocmh.overseer.getCoreContainer()).get(0).nodeName;// TODO: use replica type in this logic too
      }
    }
    log.info("Node Identified {} for creating new replica", node);

    if (!clusterState.liveNodesContain(node)) {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Node: " + node + " is not live");
    }
    if (coreName == null) {
      coreName = Assign.buildCoreName(ocmh.overseer.getSolrCloudManager().getDistribStateManager(), coll, shard, replicaType);
    } else if (!skipCreateReplicaInClusterState) {
      //Validate that the core name is unique in that collection
      for (Slice slice : coll.getSlices()) {
        for (Replica replica : slice.getReplicas()) {
          String replicaCoreName = replica.getStr(CORE_NAME_PROP);
          if (coreName.equals(replicaCoreName)) {
            throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Another replica with the same core name already exists" +
                " for this collection");
          }
        }
      }
    }
    ModifiableSolrParams params = new ModifiableSolrParams();

    ZkStateReader zkStateReader = ocmh.zkStateReader;
    if (!Overseer.isLegacy(zkStateReader)) {
      if (!skipCreateReplicaInClusterState) {
        ZkNodeProps props = new ZkNodeProps(
            Overseer.QUEUE_OPERATION, ADDREPLICA.toLower(),
            ZkStateReader.COLLECTION_PROP, collection,
            ZkStateReader.SHARD_ID_PROP, shard,
            ZkStateReader.CORE_NAME_PROP, coreName,
            ZkStateReader.STATE_PROP, Replica.State.DOWN.toString(),
            ZkStateReader.BASE_URL_PROP, zkStateReader.getBaseUrlForNodeName(node),
            ZkStateReader.NODE_NAME_PROP, node,
            ZkStateReader.REPLICA_TYPE, replicaType.name());
        if (coreNodeName != null) {
          props = props.plus(ZkStateReader.CORE_NODE_NAME_PROP, coreNodeName);
        }
        try {
          Overseer.getStateUpdateQueue(zkStateReader.getZkClient()).offer(Utils.toJSON(props));
        } catch (Exception e) {
          throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Exception updating Overseer state queue", e);
        }
      }
      params.set(CoreAdminParams.CORE_NODE_NAME,
          ocmh.waitToSeeReplicasInState(collection, Collections.singletonList(coreName)).get(coreName).getName());
    }

    String configName = zkStateReader.readConfigName(collection);
    String routeKey = message.getStr(ShardParams._ROUTE_);
    String dataDir = message.getStr(CoreAdminParams.DATA_DIR);
    String ulogDir = message.getStr(CoreAdminParams.ULOG_DIR);
    String instanceDir = message.getStr(CoreAdminParams.INSTANCE_DIR);

    params.set(CoreAdminParams.ACTION, CoreAdminParams.CoreAdminAction.CREATE.toString());
    params.set(CoreAdminParams.NAME, coreName);
    params.set(COLL_CONF, configName);
    params.set(CoreAdminParams.COLLECTION, collection);
    params.set(CoreAdminParams.REPLICA_TYPE, replicaType.name());
    if (shard != null) {
      params.set(CoreAdminParams.SHARD, shard);
    } else if (routeKey != null) {
      Collection<Slice> slices = coll.getRouter().getSearchSlicesSingle(routeKey, null, coll);
      if (slices.isEmpty()) {
        throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "No active shard serving _route_=" + routeKey + " found");
      } else {
        params.set(CoreAdminParams.SHARD, slices.iterator().next().getName());
      }
    } else {
      throw new SolrException(SolrException.ErrorCode.BAD_REQUEST, "Specify either 'shard' or _route_ param");
    }
    if (dataDir != null) {
      params.set(CoreAdminParams.DATA_DIR, dataDir);
    }
    if (ulogDir != null) {
      params.set(CoreAdminParams.ULOG_DIR, ulogDir);
    }
    if (instanceDir != null) {
      params.set(CoreAdminParams.INSTANCE_DIR, instanceDir);
    }
    if (coreNodeName != null) {
      params.set(CoreAdminParams.CORE_NODE_NAME, coreNodeName);
    }
    ocmh.addPropertyParams(message, params);

    // For tracking async calls.
    Map<String,String> requestMap = new HashMap<>();
    ocmh.sendShardRequest(node, params, shardHandler, asyncId, requestMap);

    final String fnode = node;
    final String fcoreName = coreName;

    Runnable runnable = () -> {
      ocmh.processResponses(results, shardHandler, true, "ADDREPLICA failed to create replica", asyncId, requestMap);
      ocmh.waitForCoreNodeName(collection, fnode, fcoreName);
      if (policyVersionAfter.get() > -1) {
        PolicyHelper.REF_VERSION.remove();
        ocmh.policySessionRef.decref(policyVersionAfter.get());
      }
      if (onComplete != null) onComplete.run();
    };

    if (!parallel || waitForFinalState) {
      if (waitForFinalState) {
        CountDownLatch countDownLatch = new CountDownLatch(1);
        ActiveReplicaWatcher watcher = new ActiveReplicaWatcher(collection, null, Collections.singletonList(coreName), countDownLatch);
        try {
          zkStateReader.registerCollectionStateWatcher(collection, watcher);
          runnable.run();
          if (!countDownLatch.await(timeout, TimeUnit.SECONDS)) {
            throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Timeout waiting " + timeout + " seconds for replica to become active.");
          }
        } finally {
          zkStateReader.removeCollectionStateWatcher(collection, watcher);
        }
      } else {
        runnable.run();
      }
    } else {
      ocmh.tpe.submit(runnable);
    }


    return new ZkNodeProps(
        ZkStateReader.COLLECTION_PROP, collection,
        ZkStateReader.SHARD_ID_PROP, shard,
        ZkStateReader.CORE_NAME_PROP, coreName,
        ZkStateReader.NODE_NAME_PROP, node
    );
  }

}
