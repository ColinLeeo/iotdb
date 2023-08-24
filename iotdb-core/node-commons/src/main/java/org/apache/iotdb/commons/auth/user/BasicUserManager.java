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
package org.apache.iotdb.commons.auth.user;

import org.apache.iotdb.commons.auth.AuthException;
import org.apache.iotdb.commons.auth.entity.User;
import org.apache.iotdb.commons.concurrent.HashLock;
import org.apache.iotdb.commons.conf.CommonDescriptor;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.commons.utils.AuthUtils;
import org.apache.iotdb.rpc.TSStatusCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * This class stores information of each user in a separate file within a directory, and cache them
 * in memory when a user is accessed.
 */
public abstract class BasicUserManager implements IUserManager {

  private static final Logger logger = LoggerFactory.getLogger(BasicUserManager.class);
  private static final String NO_SUCH_USER_ERROR = "No such user %s";

  protected Map<String, User> userMap;
  protected IUserAccessor accessor;
  protected HashLock lock;

  /**
   * BasicUserManager Constructor.
   *
   * @param accessor user accessor
   * @throws AuthException Authentication Exception
   */
  protected BasicUserManager(IUserAccessor accessor) throws AuthException {
    this.userMap = new HashMap<>();
    this.accessor = accessor;
    this.lock = new HashLock();

    reset();
  }

  /**
   * Try to load admin. If it doesn't exist, automatically create one
   *
   * @throws AuthException if an exception is raised when interacting with the lower storage.
   */
  // shall we really have a root user's profile in our storage?
  private void initAdmin() throws AuthException {
    User admin;
    try {
      admin = getUser(CommonDescriptor.getInstance().getConfig().getAdminName());
    } catch (AuthException e) {
      logger.warn("Cannot load admin, Creating a new one", e);
      admin = null;
    }

    if (admin == null) {
      createUser(
          CommonDescriptor.getInstance().getConfig().getAdminName(),
          CommonDescriptor.getInstance().getConfig().getAdminPassword(),
          true);
      setUserUseWaterMark(CommonDescriptor.getInstance().getConfig().getAdminName(), false);
    }
    logger.info("Admin initialized");
  }

  @Override
  public User getUser(String username) throws AuthException {
    lock.readLock(username);
    User user = userMap.get(username);
    try {
      if (user == null) {
        user = accessor.loadUser(username);
        if (user != null) {
          userMap.put(username, user);
        }
      }
    } catch (IOException e) {
      throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
    } finally {
      lock.readUnlock(username);
    }
    return user;
  }

  @Override
  public boolean createUser(String username, String password, boolean firstInit)
      throws AuthException {
    if (!firstInit) {
      AuthUtils.validateUsername(username);
      AuthUtils.validatePassword(password);
    }

    User user = getUser(username);
    if (user != null) {
      return false;
    }
    lock.writeLock(username);
    try {
      user = new User(username, AuthUtils.encryptPassword(password));
      File userDirPath = new File(accessor.getDirPath());
      if (!userDirPath.exists()) {
        reset();
      }
      accessor.saveUser(user);
      userMap.put(username, user);
      return true;
    } catch (IOException e) {
      throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public boolean deleteUser(String username) throws AuthException {
    lock.writeLock(username);
    try {
      if (accessor.deleteUser(username)) {
        userMap.remove(username);
        return true;
      } else {
        return false;
      }
    } catch (IOException e) {
      throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public boolean grantPrivilegeToUser(
      String username, PartialPath path, int privilegeId, boolean grantOpt) throws AuthException {
    AuthUtils.validatePrivilegeOnPath(path, privilegeId);
    lock.writeLock(username);
    try {
      User user = getUser(username);
      if (user == null) {
        throw new AuthException(
            TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
      }
      if (user.hasPrivilege(path, privilegeId)) {
        return false;
      }
      user.addPathPrivilege(path, privilegeId, grantOpt);
      return true;
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public boolean revokePrivilegeFromUser(String username, PartialPath path, int privilegeId)
      throws AuthException {
    AuthUtils.validatePrivilegeOnPath(path, privilegeId);
    lock.writeLock(username);
    try {
      User user = getUser(username);
      if (user == null) {
        throw new AuthException(
            TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
      }
      if (!user.hasPrivilege(path, privilegeId)) {
        return false;
      }
      user.removePathPrivilege(path, privilegeId);
      return true;
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public boolean updateUserPassword(String username, String newPassword) throws AuthException {
    try {
      AuthUtils.validatePassword(newPassword);
    } catch (AuthException e) {
      logger.debug("An illegal password detected ", e);
      return false;
    }

    lock.writeLock(username);
    try {
      User user = getUser(username);
      if (user == null) {
        throw new AuthException(
            TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
      }
      String oldPassword = user.getPassword();
      user.setPassword(AuthUtils.encryptPassword(newPassword));
      try {
        accessor.saveUser(user);
      } catch (IOException e) {
        user.setPassword(oldPassword);
        throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
      }
      return true;
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public boolean grantRoleToUser(String roleName, String username) throws AuthException {
    lock.writeLock(username);
    try {
      User user = getUser(username);
      if (user == null) {
        throw new AuthException(
            TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
      }
      if (user.hasRole(roleName)) {
        return false;
      }
      user.getRoleList().add(roleName);
      try {
        accessor.saveUser(user);
      } catch (IOException e) {
        user.getRoleList().remove(roleName);
        throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
      }
      return true;
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public boolean revokeRoleFromUser(String roleName, String username) throws AuthException {
    lock.writeLock(username);
    try {
      User user = getUser(username);
      if (user == null) {
        throw new AuthException(
            TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
      }
      if (!user.hasRole(roleName)) {
        return false;
      }
      user.getRoleList().remove(roleName);
      try {
        accessor.saveUser(user);
      } catch (IOException e) {
        user.getRoleList().add(roleName);
        throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
      }
      return true;
    } finally {
      lock.writeUnlock(username);
    }
  }

  @Override
  public void reset() throws AuthException {
    accessor.reset();
    userMap.clear();
    initAdmin();
  }

  @Override
  public List<String> listAllUsers() {
    List<String> rtlist = accessor.listAllUsers();
    rtlist.sort(null);
    return rtlist;
  }

  @Override
  public boolean isUserUseWaterMark(String username) throws AuthException {
    User user = getUser(username);
    if (user == null) {
      throw new AuthException(
          TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
    }
    return user.isUseWaterMark();
  }

  @Override
  public void setUserUseWaterMark(String username, boolean useWaterMark) throws AuthException {
    User user = getUser(username);
    if (user == null) {
      throw new AuthException(
          TSStatusCode.USER_NOT_EXIST, String.format(NO_SUCH_USER_ERROR, username));
    }
    boolean oldFlag = user.isUseWaterMark();
    if (oldFlag == useWaterMark) {
      return;
    }
    user.setUseWaterMark(useWaterMark);
    try {
      accessor.saveUser(user);
    } catch (IOException e) {
      user.setUseWaterMark(oldFlag);
      throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
    }
  }

  @Override
  public void replaceAllUsers(Map<String, User> users) throws AuthException {
    synchronized (this) {
      reset();
      userMap = users;

      for (Entry<String, User> entry : userMap.entrySet()) {
        User user = entry.getValue();
        try {
          accessor.saveUser(user);
        } catch (IOException e) {
          throw new AuthException(TSStatusCode.AUTH_IO_EXCEPTION, e);
        }
      }
    }
  }
}
