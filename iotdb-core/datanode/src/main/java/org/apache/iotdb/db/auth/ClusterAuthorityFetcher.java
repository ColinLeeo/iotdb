/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.iotdb.db.auth;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.auth.AuthException;
import org.apache.iotdb.commons.auth.entity.PathPrivilege;
import org.apache.iotdb.commons.auth.entity.PrivilegeModelType;
import org.apache.iotdb.commons.auth.entity.PrivilegeType;
import org.apache.iotdb.commons.auth.entity.Role;
import org.apache.iotdb.commons.auth.entity.User;
import org.apache.iotdb.commons.client.IClientManager;
import org.apache.iotdb.commons.client.exception.ClientManagerException;
import org.apache.iotdb.commons.conf.CommonConfig;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.consensus.ConfigRegionId;
import org.apache.iotdb.commons.exception.IoTDBException;
import org.apache.iotdb.commons.exception.MetadataException;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.path.PathPatternTree;
import org.apache.iotdb.commons.utils.AuthUtils;
import org.apache.iotdb.confignode.rpc.thrift.TAuthizedPatternTreeResp;
import org.apache.iotdb.confignode.rpc.thrift.TAuthorizerRelationalReq;
import org.apache.iotdb.confignode.rpc.thrift.TAuthorizerReq;
import org.apache.iotdb.confignode.rpc.thrift.TAuthorizerResp;
import org.apache.iotdb.confignode.rpc.thrift.TCheckUserPrivilegesReq;
import org.apache.iotdb.confignode.rpc.thrift.TLoginReq;
import org.apache.iotdb.confignode.rpc.thrift.TPathPrivilege;
import org.apache.iotdb.confignode.rpc.thrift.TPermissionInfoResp;
import org.apache.iotdb.confignode.rpc.thrift.TRoleResp;
import org.apache.iotdb.db.protocol.client.ConfigNodeClient;
import org.apache.iotdb.db.protocol.client.ConfigNodeClientManager;
import org.apache.iotdb.db.protocol.client.ConfigNodeInfo;
import org.apache.iotdb.db.queryengine.plan.execution.config.ConfigTaskResult;
import org.apache.iotdb.db.queryengine.plan.relational.sql.ast.RelationalAuthorStatement;
import org.apache.iotdb.db.queryengine.plan.statement.sys.AuthorStatement;
import org.apache.iotdb.rpc.RpcUtils;
import org.apache.iotdb.rpc.TSStatusCode;

import com.google.common.util.concurrent.SettableFuture;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ClusterAuthorityFetcher implements IAuthorityFetcher {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClusterAuthorityFetcher.class);
  private static final CommonConfig CONFIG = CommonDescriptor.getInstance().getConfig();
  private final IAuthorCache iAuthorCache;
  private boolean cacheOutDate = false;
  private long heartBeatTimeStamp = 0;

  // for test only.
  private boolean acceptCache = true;

  private static final IClientManager<ConfigRegionId, ConfigNodeClient> CONFIG_NODE_CLIENT_MANAGER =
      ConfigNodeClientManager.getInstance();

  private static final String CONNECTERROR = "Failed to connect to config node.";

  public ClusterAuthorityFetcher(IAuthorCache iAuthorCache) {
    this.iAuthorCache = iAuthorCache;
  }

  @Override
  public List<Integer> checkUserPathPrivileges(
      String username, List<? extends PartialPath> allPath, PrivilegeType permission) {
    checkCacheAvailable();
    List<Integer> posList = new ArrayList<>();
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      if (user.isOpenIdUser()) {
        return posList;
      }
      int pos = 0;
      for (PartialPath path : allPath) {
        if (!user.checkPathPrivilege(path, permission)) {
          boolean checkFromRole = false;
          for (String rolename : user.getRoleSet()) {
            Role cachedRole = iAuthorCache.getRoleCache(rolename);
            if (cachedRole == null) {
              return checkPathFromConfigNode(username, allPath, permission);
            }
            if (cachedRole.checkPathPrivilege(path, permission)) {
              checkFromRole = true;
              break;
            }
          }
          if (!checkFromRole) {
            posList.add(pos);
          }
        }
        pos++;
      }
      return posList;
    } else {
      return checkPathFromConfigNode(username, allPath, permission);
    }
  }

  @Override
  public boolean checkUserPrivilegeGrantOpt(
      String username, PrivilegeType permission, Object... targets) {
    checkCacheAvailable();

    switch (targets.length) {
      case 0:
        return checkUserSysPriGrantOpt(username, permission);
      case 1:
        if (targets[0] instanceof PartialPath) {
          return checkUserPathPriGrantOpt(
              username, Collections.singletonList((PartialPath) targets[0]), permission);
        }
        return checkUserObjectPriGrantOpt(username, (String) targets[0], "", permission);
      case 2:
        return checkUserObjectPriGrantOpt(
            username, (String) targets[0], (String) targets[1], permission);
      default:
        throw new IllegalArgumentException("Invalid number of arguments for grantopt check");
    }
  }

  private boolean checkUserPathPriGrantOpt(
      String username, List<PartialPath> paths, PrivilegeType permission) {
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      if (user.isOpenIdUser()) {
        return true;
      }
      for (PartialPath path : paths) {
        if (!user.checkPathPrivilegeGrantOpt(path, permission)) {
          boolean checkFromRole = false;
          for (String roleName : user.getRoleSet()) {
            Role role = iAuthorCache.getRoleCache(roleName);
            if (role == null) {
              return checkPathGrantOptFromConfigNode(username, paths, permission);
            }
            if (role.checkPathPrivilegeGrantOpt(path, permission)) {
              checkFromRole = true;
              break;
            }
          }
          if (!checkFromRole) {
            return false;
          }
        }
      }
      return true;
    } else {
      return checkPathGrantOptFromConfigNode(username, paths, permission);
    }
  }

  private boolean checkUserObjectPriGrantOpt(
      String username, String database, String table, PrivilegeType permission) {
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      if (user.isOpenIdUser()) {
        return true;
      }
      if (!user.checkObjectPrivilegeGrantOpt(database, table, permission)) {
        for (String roleName : user.getRoleSet()) {
          Role role = iAuthorCache.getRoleCache(roleName);
          if (role == null) {
            return checkObjectGrantOptFromConfigNode(username, database, table, permission);
          }
          if (role.checkObjectPrivilegeGrantOpt(database, table, permission)) {
            return true;
          }
        }
        return false;
      } else {
        return true;
      }
    } else {
      return checkObjectGrantOptFromConfigNode(username, database, table, permission);
    }
  }

  private boolean checkUserSysPriGrantOpt(String username, PrivilegeType permission) {
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      if (user.isOpenIdUser()) {
        return true;
      }
      if (!user.checkSysPriGrantOpt(permission)) {
        for (String roleName : user.getRoleSet()) {
          Role role = iAuthorCache.getRoleCache(roleName);
          if (role == null) {
            return checkSysGrantOptFromConfigNode(username, permission);
          }
          if (role.checkSysPriGrantOpt(permission)) {
            return true;
          }
        }
        return false;
      }
      return true;
    } else {
      return checkSysGrantOptFromConfigNode(username, permission);
    }
  }

  private boolean checkSysGrantOptFromConfigNode(String username, PrivilegeType priv) {
    TCheckUserPrivilegesReq req =
        new TCheckUserPrivilegesReq(
            username, PrivilegeModelType.SYSTEM.ordinal(), priv.ordinal(), true);
    return checkPrivilegeFromConfigNode(req).getStatus().getCode()
        == TSStatusCode.SUCCESS_STATUS.getStatusCode();
  }

  private boolean checkPathGrantOptFromConfigNode(
      String username, List<PartialPath> paths, PrivilegeType priv) {
    TCheckUserPrivilegesReq req =
        new TCheckUserPrivilegesReq(
            username, PrivilegeModelType.TREE.ordinal(), priv.ordinal(), true);
    req.setPaths(AuthUtils.serializePartialPathList(paths));
    return checkPrivilegeFromConfigNode(req).getStatus().getCode()
        == TSStatusCode.SUCCESS_STATUS.getStatusCode();
  }

  private boolean checkObjectGrantOptFromConfigNode(
      String username, String database, String table, PrivilegeType priv) {
    TCheckUserPrivilegesReq req =
        new TCheckUserPrivilegesReq(
            username, PrivilegeModelType.RELATIONAL.ordinal(), priv.ordinal(), true);
    req.setDatabase(database);
    if (!table.isEmpty()) {
      req.setTable(table);
    }
    return checkPrivilegeFromConfigNode(req).getStatus().getCode()
        == TSStatusCode.SUCCESS_STATUS.getStatusCode();
  }

  @Override
  public PathPatternTree getAuthorizedPatternTree(String username, PrivilegeType permission)
      throws AuthException {
    PathPatternTree patternTree = new PathPatternTree();
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      for (PathPrivilege path : user.getPathPrivilegeList()) {
        if (path.checkPrivilege(permission)) {
          patternTree.appendPathPattern(path.getPath());
        }
      }
      for (String roleName : user.getRoleSet()) {
        Role role = iAuthorCache.getRoleCache(roleName);
        if (role != null) {
          for (PathPrivilege path : role.getPathPrivilegeList()) {
            if (path.checkPrivilege(permission)) {
              patternTree.appendPathPattern(path.getPath());
            }
          }
        } else {
          return fetchAuthizedPatternTree(username, permission);
        }
      }
      patternTree.constructTree();
      return patternTree;
    } else {
      return fetchAuthizedPatternTree(username, permission);
    }
  }

  private PathPatternTree fetchAuthizedPatternTree(String username, PrivilegeType permission)
      throws AuthException {
    TCheckUserPrivilegesReq req =
        new TCheckUserPrivilegesReq(
            username, PrivilegeModelType.TREE.ordinal(), permission.ordinal(), false);
    TAuthizedPatternTreeResp authizedPatternTree = new TAuthizedPatternTreeResp();
    try (ConfigNodeClient configNodeClient =
        CONFIG_NODE_CLIENT_MANAGER.borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
      authizedPatternTree = configNodeClient.fetchAuthizedPatternTree(req);
    } catch (ClientManagerException | TException e) {
      LOGGER.error(CONNECTERROR);
      authizedPatternTree.setStatus(
          RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, CONNECTERROR));
    }
    if (authizedPatternTree.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      if (acceptCache) {
        iAuthorCache.putUserCache(username, cacheUser(authizedPatternTree.getPermissionInfo()));
      }
      return PathPatternTree.deserialize(ByteBuffer.wrap(authizedPatternTree.getPathPatternTree()));
    } else {
      throw new AuthException(
          TSStatusCode.EXECUTE_STATEMENT_ERROR, authizedPatternTree.getStatus().getMessage());
    }
  }

  @Override
  public TSStatus checkUserSysPrivileges(String username, PrivilegeType permission) {
    checkCacheAvailable();
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      if (!user.isOpenIdUser() && (!user.checkSysPrivilege(permission))) {
        if (user.getRoleSet().isEmpty()) {
          return RpcUtils.getStatus(TSStatusCode.NO_PERMISSION);
        }
        boolean status = false;
        for (String rolename : user.getRoleSet()) {
          Role cacheRole = iAuthorCache.getRoleCache(rolename);
          if (cacheRole == null) {
            return checkSysPriFromConfigNode(username, permission);
          }
          if (cacheRole.checkSysPrivilege(permission)) {
            status = true;
            break;
          }
        }
        if (!status) {
          return RpcUtils.getStatus(TSStatusCode.NO_PERMISSION);
        }
      }
      return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
    } else {
      return checkSysPriFromConfigNode(username, permission);
    }
  }

  private SettableFuture<ConfigTaskResult> operatePermissionInternal(
      Object plan, boolean isRelational) {
    SettableFuture<ConfigTaskResult> future = SettableFuture.create();
    try (ConfigNodeClient configNodeClient =
        CONFIG_NODE_CLIENT_MANAGER.borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
      TSStatus tsStatus =
          isRelational
              ? configNodeClient.operateRPermission(
                  statementToAuthorizerReq((RelationalAuthorStatement) plan))
              : configNodeClient.operatePermission(
                  statementToAuthorizerReq((AuthorStatement) plan));
      if (TSStatusCode.SUCCESS_STATUS.getStatusCode() == tsStatus.getCode()) {
        future.setException(new IoTDBException(tsStatus.message, tsStatus.code));
      } else {
        future.set(new ConfigTaskResult(TSStatusCode.SUCCESS_STATUS));
      }
    } catch (AuthException e) {
      future.setException(e);
    } catch (ClientManagerException | TException e) {
      LOGGER.error(CONNECTERROR);
      future.setException(e);
    }
    return future;
  }

  @Override
  public SettableFuture<ConfigTaskResult> operatePermission(AuthorStatement authorStatement) {
    return operatePermissionInternal(authorStatement, false);
  }

  @Override
  public SettableFuture<ConfigTaskResult> operatePermission(
      RelationalAuthorStatement authorStatement) {
    return operatePermissionInternal(authorStatement, true);
  }

  private SettableFuture<ConfigTaskResult> queryPermissionInternal(
      Object plan, boolean isRelational) {
    SettableFuture<ConfigTaskResult> future = SettableFuture.create();
    TAuthorizerResp authorizerResp = new TAuthorizerResp();
    try (ConfigNodeClient configNodeClient =
        CONFIG_NODE_CLIENT_MANAGER.borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
      authorizerResp =
          isRelational
              ? configNodeClient.queryRPermission(
                  statementToAuthorizerReq((RelationalAuthorStatement) plan))
              : configNodeClient.queryPermission(statementToAuthorizerReq((AuthorStatement) plan));
      if (TSStatusCode.SUCCESS_STATUS.getStatusCode() != authorizerResp.getStatus().getCode()) {
        future.setException(
            new IoTDBException(
                authorizerResp.getStatus().message, authorizerResp.getStatus().code));
      } else {
        AuthorityChecker.buildTSBlock(authorizerResp, future);
      }
    } catch (AuthException e) {
      future.setException(e);
    } catch (ClientManagerException | TException e) {
      LOGGER.error(CONNECTERROR);
      authorizerResp.setStatus(
          RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, CONNECTERROR));
      future.setException(
          new IoTDBException(authorizerResp.getStatus().message, authorizerResp.getStatus().code));
    }
    return future;
  }

  @Override
  public SettableFuture<ConfigTaskResult> queryPermission(AuthorStatement authorStatement) {
    return queryPermissionInternal(authorStatement, false);
  }

  @Override
  public SettableFuture<ConfigTaskResult> queryPermission(
      RelationalAuthorStatement authorStatement) {
    return queryPermissionInternal(authorStatement, true);
  }

  @Override
  public IAuthorCache getAuthorCache() {
    return iAuthorCache;
  }

  @Override
  public void refreshToken() {
    long currentTime = System.currentTimeMillis();
    if (heartBeatTimeStamp == 0) {
      heartBeatTimeStamp = currentTime;
      return;
    }
    if (currentTime - heartBeatTimeStamp > CONFIG.getDatanodeTokenTimeoutMS()) {
      cacheOutDate = true;
    }
    heartBeatTimeStamp = currentTime;
  }

  private void checkCacheAvailable() {
    if (cacheOutDate) {
      iAuthorCache.invalidAllCache();
    }
    cacheOutDate = false;
  }

  @Override
  public TSStatus checkUser(String username, String password) {
    checkCacheAvailable();
    User user = iAuthorCache.getUserCache(username);
    if (user != null) {
      if (user.isOpenIdUser()) {
        return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
      } else if (password != null && AuthUtils.validatePassword(password, user.getPassword())) {
        return RpcUtils.getStatus(TSStatusCode.SUCCESS_STATUS);
      } else {
        return RpcUtils.getStatus(TSStatusCode.WRONG_LOGIN_PASSWORD, "Authentication failed.");
      }
    } else {
      TLoginReq req = new TLoginReq(username, password);
      TPermissionInfoResp status = null;
      try (ConfigNodeClient configNodeClient =
          CONFIG_NODE_CLIENT_MANAGER.borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
        // Send request to some API server
        status = configNodeClient.login(req);
      } catch (ClientManagerException | TException e) {
        LOGGER.error(CONNECTERROR);
        status = new TPermissionInfoResp();
        status.setStatus(RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, CONNECTERROR));
      } finally {
        if (status == null) {
          status = new TPermissionInfoResp();
        }
      }
      if (status.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
        if (acceptCache) {
          iAuthorCache.putUserCache(username, cacheUser(status));
        }
        return status.getStatus();
      } else {
        return status.getStatus();
      }
    }
  }

  @Override
  public boolean checkRole(String userName, String roleName) {
    checkCacheAvailable();
    User user = iAuthorCache.getUserCache(userName);
    if (user != null) {
      return user.isOpenIdUser() || user.getRoleSet().contains(roleName);
    } else {
      return checkRoleFromConfigNode(userName, roleName);
    }
  }

  private TPermissionInfoResp checkPrivilegeFromConfigNode(TCheckUserPrivilegesReq req) {
    TPermissionInfoResp permissionInfoResp;
    try (ConfigNodeClient configNodeClient =
        CONFIG_NODE_CLIENT_MANAGER.borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
      // Send request to some API server
      permissionInfoResp = configNodeClient.checkUserPrivileges(req);
    } catch (ClientManagerException | TException e) {
      LOGGER.error(CONNECTERROR);
      permissionInfoResp = new TPermissionInfoResp();
      permissionInfoResp.setStatus(
          RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, CONNECTERROR));
    }
    if (permissionInfoResp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      if (acceptCache) {
        iAuthorCache.putUserCache(req.getUsername(), cacheUser(permissionInfoResp));
      }
    }
    return permissionInfoResp;
  }

  private TSStatus checkSysPriFromConfigNode(String username, PrivilegeType permission) {
    TCheckUserPrivilegesReq req =
        new TCheckUserPrivilegesReq(
            username, PrivilegeModelType.SYSTEM.ordinal(), permission.ordinal(), false);
    return checkPrivilegeFromConfigNode(req).getStatus();
  }

  private List<Integer> checkPathFromConfigNode(
      String username, List<? extends PartialPath> allPath, PrivilegeType permission) {
    TCheckUserPrivilegesReq req =
        new TCheckUserPrivilegesReq(
            username, PrivilegeModelType.TREE.ordinal(), permission.ordinal(), false);
    req.setPaths(AuthUtils.serializePartialPathList(allPath));
    return checkPrivilegeFromConfigNode(req).getFailPos();
  }

  private boolean checkRoleFromConfigNode(String username, String rolename) {
    TAuthorizerReq req = new TAuthorizerReq();
    // just reuse authorizer request. only need username and rolename field.
    req.setAuthorType(0);
    req.setPassword("");
    req.setNewPassword("");
    req.setNodeNameList(AuthUtils.serializePartialPathList(Collections.emptyList()));
    req.setPermissions(Collections.emptySet());
    req.setGrantOpt(false);
    req.setUserName(username);
    req.setRoleName(rolename);
    TPermissionInfoResp permissionInfoResp;
    try (ConfigNodeClient configNodeClient =
        CONFIG_NODE_CLIENT_MANAGER.borrowClient(ConfigNodeInfo.CONFIG_REGION_ID)) {
      // Send request to some API server
      permissionInfoResp = configNodeClient.checkRoleOfUser(req);
    } catch (ClientManagerException | TException e) {
      LOGGER.error(CONNECTERROR);
      permissionInfoResp = new TPermissionInfoResp();
      permissionInfoResp.setStatus(
          RpcUtils.getStatus(TSStatusCode.EXECUTE_STATEMENT_ERROR, CONNECTERROR));
    }
    if (permissionInfoResp.getStatus().getCode() == TSStatusCode.SUCCESS_STATUS.getStatusCode()) {
      if (acceptCache) {
        iAuthorCache.putUserCache(username, cacheUser(permissionInfoResp));
      }
      return true;
    } else if (permissionInfoResp.getStatus().getCode()
        == TSStatusCode.USER_NOT_HAS_ROLE.getStatusCode()) {
      if (acceptCache) {
        iAuthorCache.putUserCache(username, cacheUser(permissionInfoResp));
      }
      return false;
    } else {
      return false;
    }
  }

  /** Cache user. */
  public User cacheUser(TPermissionInfoResp tPermissionInfoResp) {
    User user = new User();
    List<TPathPrivilege> privilegeList =
        tPermissionInfoResp.getUserInfo().getBasicInfo().getPrivilegeList();
    user.setName(tPermissionInfoResp.getUserInfo().getBasicInfo().getName());
    user.setPassword(tPermissionInfoResp.getUserInfo().getPassword());
    user.loadRelationalPrivileInfo(
        tPermissionInfoResp.getUserInfo().getBasicInfo().getDbPrivilegeMap());
    user.setOpenIdUser(tPermissionInfoResp.getUserInfo().isIsOpenIdUser());
    user.setRoleSet(tPermissionInfoResp.getUserInfo().getRoleSet());
    user.setSysPrivilegeSetInt(tPermissionInfoResp.getUserInfo().getBasicInfo().getSysPriSet());
    user.setSysPriGrantOptInt(
        tPermissionInfoResp.getUserInfo().getBasicInfo().getSysPriSetGrantOpt());
    try {
      user.loadPathPrivilegeInfo(privilegeList);
    } catch (MetadataException e) {
      LOGGER.error("cache user's path privileges error", e);
    }
    if (tPermissionInfoResp.isSetRoleInfo()) {
      for (String roleName : tPermissionInfoResp.getRoleInfo().keySet()) {
        iAuthorCache.putRoleCache(roleName, cacheRole(roleName, tPermissionInfoResp));
      }
    }
    return user;
  }

  /** Cache role. */
  public Role cacheRole(String roleName, TPermissionInfoResp tPermissionInfoResp) {
    TRoleResp resp = tPermissionInfoResp.getRoleInfo().get(roleName);
    Role role = new Role(resp.getName());

    role.loadRelationalPrivileInfo(resp.getDbPrivilegeMap());
    role.setSysPriGrantOptInt(
        tPermissionInfoResp.getRoleInfo().get(roleName).getSysPriSetGrantOpt());
    role.setSysPrivilegeSetInt(tPermissionInfoResp.getRoleInfo().get(roleName).getSysPriSet());
    try {
      role.loadPathPrivilegeInfo(resp.getPrivilegeList());
    } catch (MetadataException e) {
      LOGGER.error("cache role's path privileges error", e);
    }
    return role;
  }

  private TAuthorizerReq statementToAuthorizerReq(AuthorStatement authorStatement)
      throws AuthException {
    if (authorStatement.getAuthorType() == null) {
      authorStatement.setNodeNameList(new ArrayList<>());
    }
    return new TAuthorizerReq(
        authorStatement.getAuthorType().ordinal(),
        authorStatement.getUserName() == null ? "" : authorStatement.getUserName(),
        authorStatement.getRoleName() == null ? "" : authorStatement.getRoleName(),
        authorStatement.getPassWord() == null ? "" : authorStatement.getPassWord(),
        authorStatement.getNewPassword() == null ? "" : authorStatement.getNewPassword(),
        AuthUtils.strToPermissions(authorStatement.getPrivilegeList()),
        authorStatement.getGrantOpt(),
        AuthUtils.serializePartialPathList(authorStatement.getNodeNameList()));
  }

  private TAuthorizerRelationalReq statementToAuthorizerReq(
      RelationalAuthorStatement authorStatement) {
    return new TAuthorizerRelationalReq(
        authorStatement.getAuthorType().ordinal(),
        authorStatement.getUserName() == null ? "" : authorStatement.getUserName(),
        authorStatement.getRoleName() == null ? "" : authorStatement.getRoleName(),
        authorStatement.getPassword() == null ? "" : authorStatement.getPassword(),
        authorStatement.getDatabase() == null ? "" : authorStatement.getDatabase(),
        authorStatement.getTableName() == null ? "" : authorStatement.getTableName(),
        authorStatement.getPrivilegeType().ordinal(),
        authorStatement.hasGrantOption());
  }
}
