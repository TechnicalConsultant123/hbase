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

package org.apache.hadoop.hbase.rsgroup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.HBaseIOException;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.constraint.ConstraintException;
import org.apache.hadoop.hbase.master.LoadBalancer;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.master.balancer.StochasticLoadBalancer;
import org.apache.hadoop.hbase.net.Address;
import org.apache.hadoop.hbase.shaded.com.google.common.annotations.VisibleForTesting;
import org.apache.hadoop.hbase.shaded.com.google.common.collect.ArrayListMultimap;
import org.apache.hadoop.hbase.shaded.com.google.common.collect.LinkedListMultimap;
import org.apache.hadoop.hbase.shaded.com.google.common.collect.ListMultimap;
import org.apache.hadoop.hbase.shaded.com.google.common.collect.Lists;
import org.apache.hadoop.hbase.shaded.com.google.common.collect.Maps;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.yetus.audience.InterfaceAudience;

/**
 * GroupBasedLoadBalancer, used when Region Server Grouping is configured (HBase-6721)
 * It does region balance based on a table's group membership.
 *
 * Most assignment methods contain two exclusive code paths: Online - when the group
 * table is online and Offline - when it is unavailable.
 *
 * During Offline, assignments are assigned based on cached information in zookeeper.
 * If unavailable (ie bootstrap) then regions are assigned randomly.
 *
 * Once the GROUP table has been assigned, the balancer switches to Online and will then
 * start providing appropriate assignments for user tables.
 *
 */
@InterfaceAudience.Private
public class RSGroupBasedLoadBalancer implements RSGroupableBalancer {
  private static final Log LOG = LogFactory.getLog(RSGroupBasedLoadBalancer.class);

  private Configuration config;
  private ClusterStatus clusterStatus;
  private MasterServices masterServices;
  private volatile RSGroupInfoManager rsGroupInfoManager;
  private LoadBalancer internalBalancer;

  /**
   * Used by reflection in {@link org.apache.hadoop.hbase.master.balancer.LoadBalancerFactory}.
   */
  @InterfaceAudience.Private
  public RSGroupBasedLoadBalancer() {}

  @Override
  public Configuration getConf() {
    return config;
  }

  @Override
  public void setConf(Configuration conf) {
    this.config = conf;
  }

  @Override
  public void setClusterStatus(ClusterStatus st) {
    this.clusterStatus = st;
  }

  @Override
  public void setMasterServices(MasterServices masterServices) {
    this.masterServices = masterServices;
  }

  @Override
  public List<RegionPlan> balanceCluster(TableName tableName, Map<ServerName, List<RegionInfo>>
      clusterState) throws HBaseIOException {
    return balanceCluster(clusterState);
  }

  @Override
  public List<RegionPlan> balanceCluster(Map<ServerName, List<RegionInfo>> clusterState)
      throws HBaseIOException {
    if (!isOnline()) {
      throw new ConstraintException(RSGroupInfoManager.RSGROUP_TABLE_NAME +
          " is not online, unable to perform balance");
    }

    Map<ServerName,List<RegionInfo>> correctedState = correctAssignments(clusterState);
    List<RegionPlan> regionPlans = new ArrayList<>();

    List<RegionInfo> misplacedRegions = correctedState.get(LoadBalancer.BOGUS_SERVER_NAME);
    for (RegionInfo regionInfo : misplacedRegions) {
      ServerName serverName = findServerForRegion(clusterState, regionInfo);
      regionPlans.add(new RegionPlan(regionInfo, serverName, null));
    }
    try {
      List<RSGroupInfo> rsgi = rsGroupInfoManager.listRSGroups();
      for (RSGroupInfo info: rsgi) {
        Map<ServerName, List<RegionInfo>> groupClusterState = new HashMap<>();
        Map<TableName, Map<ServerName, List<RegionInfo>>> groupClusterLoad = new HashMap<>();
        for (Address sName : info.getServers()) {
          for(ServerName curr: clusterState.keySet()) {
            if(curr.getAddress().equals(sName)) {
              groupClusterState.put(curr, correctedState.get(curr));
            }
          }
        }
        groupClusterLoad.put(HConstants.ENSEMBLE_TABLE_NAME, groupClusterState);
        this.internalBalancer.setClusterLoad(groupClusterLoad);
        List<RegionPlan> groupPlans = this.internalBalancer
            .balanceCluster(groupClusterState);
        if (groupPlans != null) {
          regionPlans.addAll(groupPlans);
        }
      }
    } catch (IOException exp) {
      LOG.warn("Exception while balancing cluster.", exp);
      regionPlans.clear();
    }
    return regionPlans;
  }

  @Override
  public Map<ServerName, List<RegionInfo>> roundRobinAssignment(
      List<RegionInfo> regions, List<ServerName> servers) throws HBaseIOException {
    Map<ServerName, List<RegionInfo>> assignments = Maps.newHashMap();
    ListMultimap<String,RegionInfo> regionMap = ArrayListMultimap.create();
    ListMultimap<String,ServerName> serverMap = ArrayListMultimap.create();
    generateGroupMaps(regions, servers, regionMap, serverMap);
    for(String groupKey : regionMap.keySet()) {
      if (regionMap.get(groupKey).size() > 0) {
        Map<ServerName, List<RegionInfo>> result =
            this.internalBalancer.roundRobinAssignment(
                regionMap.get(groupKey),
                serverMap.get(groupKey));
        if(result != null) {
          if(result.containsKey(LoadBalancer.BOGUS_SERVER_NAME) &&
              assignments.containsKey(LoadBalancer.BOGUS_SERVER_NAME)){
            assignments.get(LoadBalancer.BOGUS_SERVER_NAME).addAll(
              result.get(LoadBalancer.BOGUS_SERVER_NAME));
          } else {
            assignments.putAll(result);
          }
        }
      }
    }
    return assignments;
  }

  @Override
  public Map<ServerName, List<RegionInfo>> retainAssignment(
      Map<RegionInfo, ServerName> regions, List<ServerName> servers) throws HBaseIOException {
    try {
      Map<ServerName, List<RegionInfo>> assignments = new TreeMap<>();
      ListMultimap<String, RegionInfo> groupToRegion = ArrayListMultimap.create();
      Set<RegionInfo> misplacedRegions = getMisplacedRegions(regions);
      for (RegionInfo region : regions.keySet()) {
        if (!misplacedRegions.contains(region)) {
          String groupName = rsGroupInfoManager.getRSGroupOfTable(region.getTable());
          groupToRegion.put(groupName, region);
        }
      }
      // Now the "groupToRegion" map has only the regions which have correct
      // assignments.
      for (String key : groupToRegion.keySet()) {
        Map<RegionInfo, ServerName> currentAssignmentMap = new TreeMap<RegionInfo, ServerName>();
        List<RegionInfo> regionList = groupToRegion.get(key);
        RSGroupInfo info = rsGroupInfoManager.getRSGroup(key);
        List<ServerName> candidateList = filterOfflineServers(info, servers);
        for (RegionInfo region : regionList) {
          currentAssignmentMap.put(region, regions.get(region));
        }
        if(candidateList.size() > 0) {
          assignments.putAll(this.internalBalancer.retainAssignment(
              currentAssignmentMap, candidateList));
        }
      }

      for (RegionInfo region : misplacedRegions) {
        String groupName = rsGroupInfoManager.getRSGroupOfTable(region.getTable());
        RSGroupInfo info = rsGroupInfoManager.getRSGroup(groupName);
        List<ServerName> candidateList = filterOfflineServers(info, servers);
        ServerName server = this.internalBalancer.randomAssignment(region,
            candidateList);
        if (server != null) {
          if (!assignments.containsKey(server)) {
            assignments.put(server, new ArrayList<>());
          }
          assignments.get(server).add(region);
        } else {
          //if not server is available assign to bogus so it ends up in RIT
          if(!assignments.containsKey(LoadBalancer.BOGUS_SERVER_NAME)) {
            assignments.put(LoadBalancer.BOGUS_SERVER_NAME, new ArrayList<>());
          }
          assignments.get(LoadBalancer.BOGUS_SERVER_NAME).add(region);
        }
      }
      return assignments;
    } catch (IOException e) {
      throw new HBaseIOException("Failed to do online retain assignment", e);
    }
  }

  @Override
  public ServerName randomAssignment(RegionInfo region,
      List<ServerName> servers) throws HBaseIOException {
    ListMultimap<String,RegionInfo> regionMap = LinkedListMultimap.create();
    ListMultimap<String,ServerName> serverMap = LinkedListMultimap.create();
    generateGroupMaps(Lists.newArrayList(region), servers, regionMap, serverMap);
    List<ServerName> filteredServers = serverMap.get(regionMap.keySet().iterator().next());
    return this.internalBalancer.randomAssignment(region, filteredServers);
  }

  private void generateGroupMaps(
    List<RegionInfo> regions,
    List<ServerName> servers,
    ListMultimap<String, RegionInfo> regionMap,
    ListMultimap<String, ServerName> serverMap) throws HBaseIOException {
    try {
      for (RegionInfo region : regions) {
        String groupName = rsGroupInfoManager.getRSGroupOfTable(region.getTable());
        if (groupName == null) {
          LOG.warn("Group for table "+region.getTable()+" is null");
        }
        regionMap.put(groupName, region);
      }
      for (String groupKey : regionMap.keySet()) {
        RSGroupInfo info = rsGroupInfoManager.getRSGroup(groupKey);
        serverMap.putAll(groupKey, filterOfflineServers(info, servers));
        if(serverMap.get(groupKey).size() < 1) {
          serverMap.put(groupKey, LoadBalancer.BOGUS_SERVER_NAME);
        }
      }
    } catch(IOException e) {
      throw new HBaseIOException("Failed to generate group maps", e);
    }
  }

  private List<ServerName> filterOfflineServers(RSGroupInfo RSGroupInfo,
                                                List<ServerName> onlineServers) {
    if (RSGroupInfo != null) {
      return filterServers(RSGroupInfo.getServers(), onlineServers);
    } else {
      LOG.warn("RSGroup Information found to be null. Some regions might be unassigned.");
      return Collections.EMPTY_LIST;
    }
  }

  /**
   * Filter servers based on the online servers.
   *
   * @param servers
   *          the servers
   * @param onlineServers
   *          List of servers which are online.
   * @return the list
   */
  private List<ServerName> filterServers(Collection<Address> servers,
      Collection<ServerName> onlineServers) {
    ArrayList<ServerName> finalList = new ArrayList<ServerName>();
    for (Address server : servers) {
      for(ServerName curr: onlineServers) {
        if(curr.getAddress().equals(server)) {
          finalList.add(curr);
        }
      }
    }
    return finalList;
  }

  private Set<RegionInfo> getMisplacedRegions(
      Map<RegionInfo, ServerName> regions) throws IOException {
    Set<RegionInfo> misplacedRegions = new HashSet<>();
    for(Map.Entry<RegionInfo, ServerName> region : regions.entrySet()) {
      RegionInfo regionInfo = region.getKey();
      ServerName assignedServer = region.getValue();
      RSGroupInfo info = rsGroupInfoManager.getRSGroup(rsGroupInfoManager.
              getRSGroupOfTable(regionInfo.getTable()));
      if (assignedServer != null &&
          (info == null || !info.containsServer(assignedServer.getAddress()))) {
        RSGroupInfo otherInfo = null;
        otherInfo = rsGroupInfoManager.getRSGroupOfServer(assignedServer.getAddress());
        LOG.debug("Found misplaced region: " + regionInfo.getRegionNameAsString() +
            " on server: " + assignedServer +
            " found in group: " +  otherInfo +
            " outside of group: " + (info == null ? "UNKNOWN" : info.getName()));
        misplacedRegions.add(regionInfo);
      }
    }
    return misplacedRegions;
  }

  private ServerName findServerForRegion(
      Map<ServerName, List<RegionInfo>> existingAssignments, RegionInfo region)
  {
    for (Map.Entry<ServerName, List<RegionInfo>> entry : existingAssignments.entrySet()) {
      if (entry.getValue().contains(region)) {
        return entry.getKey();
      }
    }

    throw new IllegalStateException("Could not find server for region "
        + region.getShortNameToLog());
  }

  private Map<ServerName, List<RegionInfo>> correctAssignments(
       Map<ServerName, List<RegionInfo>> existingAssignments)
  throws HBaseIOException{
    Map<ServerName, List<RegionInfo>> correctAssignments = new TreeMap<>();
    correctAssignments.put(LoadBalancer.BOGUS_SERVER_NAME, new LinkedList<>());
    for (Map.Entry<ServerName, List<RegionInfo>> assignments : existingAssignments.entrySet()){
      ServerName sName = assignments.getKey();
      correctAssignments.put(sName, new LinkedList<>());
      List<RegionInfo> regions = assignments.getValue();
      for (RegionInfo region : regions) {
        RSGroupInfo info = null;
        try {
          info = rsGroupInfoManager.getRSGroup(
              rsGroupInfoManager.getRSGroupOfTable(region.getTable()));
        } catch (IOException exp) {
          LOG.debug("RSGroup information null for region of table " + region.getTable(),
              exp);
        }
        if ((info == null) || (!info.containsServer(sName.getAddress()))) {
          correctAssignments.get(LoadBalancer.BOGUS_SERVER_NAME).add(region);
        } else {
          correctAssignments.get(sName).add(region);
        }
      }
    }
    return correctAssignments;
  }

  @Override
  public void initialize() throws HBaseIOException {
    try {
      if (rsGroupInfoManager == null) {
        List<RSGroupAdminEndpoint> cps =
          masterServices.getMasterCoprocessorHost().findCoprocessors(RSGroupAdminEndpoint.class);
        if (cps.size() != 1) {
          String msg = "Expected one implementation of GroupAdminEndpoint but found " + cps.size();
          LOG.error(msg);
          throw new HBaseIOException(msg);
        }
        rsGroupInfoManager = cps.get(0).getGroupInfoManager();
      }
    } catch (IOException e) {
      throw new HBaseIOException("Failed to initialize GroupInfoManagerImpl", e);
    }

    // Create the balancer
    Class<? extends LoadBalancer> balancerKlass = config.getClass(HBASE_RSGROUP_LOADBALANCER_CLASS,
        StochasticLoadBalancer.class, LoadBalancer.class);
    internalBalancer = ReflectionUtils.newInstance(balancerKlass, config);
    internalBalancer.setMasterServices(masterServices);
    internalBalancer.setClusterStatus(clusterStatus);
    internalBalancer.setConf(config);
    internalBalancer.initialize();
  }

  public boolean isOnline() {
    if (this.rsGroupInfoManager == null) return false;
    return this.rsGroupInfoManager.isOnline();
  }

  @Override
  public void setClusterLoad(Map<TableName, Map<ServerName, List<RegionInfo>>> clusterLoad) {
  }

  @Override
  public void regionOnline(RegionInfo regionInfo, ServerName sn) {
  }

  @Override
  public void regionOffline(RegionInfo regionInfo) {
  }

  @Override
  public void onConfigurationChange(Configuration conf) {
    //DO nothing for now
  }

  @Override
  public void stop(String why) {
  }

  @Override
  public boolean isStopped() {
    return false;
  }

  @VisibleForTesting
  public void setRsGroupInfoManager(RSGroupInfoManager rsGroupInfoManager) {
    this.rsGroupInfoManager = rsGroupInfoManager;
  }
}
