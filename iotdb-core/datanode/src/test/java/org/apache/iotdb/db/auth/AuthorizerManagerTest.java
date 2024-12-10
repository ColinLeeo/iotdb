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

import org.apache.iotdb.commons.auth.entity.ModelType;
import org.apache.iotdb.commons.auth.entity.PathPrivilege;
import org.apache.iotdb.commons.auth.entity.PrivilegeType;
import org.apache.iotdb.commons.auth.entity.Role;
import org.apache.iotdb.commons.auth.entity.User;
import org.apache.iotdb.commons.exception.IllegalPathException;
import org.apache.iotdb.commons.path.PartialPath;
import org.apache.iotdb.confignode.rpc.thrift.TPermissionInfoResp;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AuthorizerManagerTest {

  ClusterAuthorityFetcher authorityFetcher = new ClusterAuthorityFetcher(new BasicAuthorityCache());

  @Test
  public void permissionCacheTest() throws IllegalPathException {
    User user = new User();
    Role role1 = new Role();
    Role role2 = new Role();
    Set<String> roleSet = new HashSet<>();
    Set<PrivilegeType> privilegesIds = new HashSet<>();
    Set<PrivilegeType> sysPriIds = new HashSet<>();
    PathPrivilege privilege = new PathPrivilege(new PartialPath("root.ln"));
    List<PathPrivilege> privilegeList = new ArrayList<>();
    privilegesIds.add(PrivilegeType.READ_DATA);
    sysPriIds.add(PrivilegeType.MAINTAIN);

    privilege.setPrivileges(privilegesIds);
    privilegeList.add(privilege);

    role1.setName("role1");
    role1.setPrivilegeList(privilegeList);

    role2.setName("role2");
    role2.setPrivilegeList(new ArrayList<>());

    roleSet.add("role1");
    roleSet.add("role2");

    user.setName("user");
    user.setPassword("password");
    user.setPrivilegeList(privilegeList);
    user.setSysPrivilegeSet(sysPriIds);
    user.setRoleSet(roleSet);
    user.grantDBPrivilege("test", PrivilegeType.ALTER, false);
    user.grantTBPrivilege("test", "table", PrivilegeType.SELECT, true);
    user.grantDBPrivilege("test2", PrivilegeType.SELECT, true);

    TPermissionInfoResp result = new TPermissionInfoResp();
    result.setUserInfo(user.getUserInfo(ModelType.ALL));
    result.setRoleInfo(new HashMap<>());

    // User authentication permission without role
    authorityFetcher
        .getAuthorCache()
        .putUserCache(user.getName(), authorityFetcher.cacheUser(result));
    User user1 = authorityFetcher.getAuthorCache().getUserCache(user.getName());
    assert user1 != null;
    Assert.assertEquals(user, user1);

    // Authenticate users with roles
    authorityFetcher.getAuthorCache().invalidateCache(user.getName(), "");
    result = new TPermissionInfoResp();
    result.setUserInfo(user.getUserInfo(ModelType.ALL));
    result.putToRoleInfo(role1.getName(), role1.getRoleInfo(ModelType.ALL));
    result.putToRoleInfo(role2.getName(), role2.getRoleInfo(ModelType.ALL));

    // Permission information for roles owned by users
    authorityFetcher
        .getAuthorCache()
        .putUserCache(user.getName(), authorityFetcher.cacheUser(result));
    Role role3 = authorityFetcher.getAuthorCache().getRoleCache(role1.getName());
    Assert.assertEquals(role1, role3);

    authorityFetcher.getAuthorCache().invalidateCache(user1.getName(), "");

    user1 = authorityFetcher.getAuthorCache().getUserCache(user.getName());
    role1 = authorityFetcher.getAuthorCache().getRoleCache(role1.getName());

    Assert.assertNull(user1);
    Assert.assertNull(role1);
  }

  @Test
  public void grantOptTest() throws IllegalPathException {
    User user = new User("user1", "123456");
    Role role = new Role("role1");

    user.grantSysPrivilege(PrivilegeType.MANAGE_DATABASE, false);
    user.grantSysPrivilege(PrivilegeType.USE_PIPE, true);

    user.grantPathPrivilege(new PartialPath("root.d1.**"), PrivilegeType.READ_DATA, false);
    user.grantPathPrivilege(new PartialPath("root.d1.**"), PrivilegeType.WRITE_SCHEMA, true);

    user.grantDBPrivilege("database", PrivilegeType.SELECT, false);
    user.grantDBPrivilege("database", PrivilegeType.ALTER, true);
    user.grantTBPrivilege("database", "table", PrivilegeType.DELETE, true);
    role.grantSysPrivilege(PrivilegeType.USE_UDF, false);
    role.grantSysPrivilege(PrivilegeType.USE_CQ, true);
    role.grantSysPrivilegeGrantOption(PrivilegeType.USE_CQ);
    role.grantPathPrivilege(new PartialPath("root.t.**"), PrivilegeType.READ_DATA, true);
    role.grantDBPrivilege("database", PrivilegeType.INSERT, true);

    // user's priv:
    // 1. MANAGE_DATABASE
    // 2. USE_PIPE with grant option
    // 3. READ_DATA root.d1.**
    // 4. WRITE_SCHEMA root.d1.** with grant option
    // 5. SELECT on database, ALTER on database with grant option
    // 6. UPDATE on database.table, DELETE on database.table with grant option

    // role's priv:
    // 1. USE_UDF
    // 2. USE_CQ with grant option
    // 3. READ_DATA root.t9.** with grant option
    user.addRole("role1");

    authorityFetcher.getAuthorCache().putUserCache("user1", user);
    authorityFetcher.getAuthorCache().putRoleCache("role1", role);

    // for system priv. we have USE_PIPE grant option.
    Assert.assertTrue(authorityFetcher.checkUserPrivilegeGrantOpt("user1", PrivilegeType.USE_PIPE));
    Assert.assertFalse(
        authorityFetcher.checkUserPrivilegeGrantOpt("user1", PrivilegeType.MANAGE_USER));

    // for path priv. we have write_schema on root.d1.** with grant option.
    // require root.d1.** with write_schema, return true
    Assert.assertTrue(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.WRITE_SCHEMA, new PartialPath("root.d1.**")));
    // require root.** with write_schema, return false
    Assert.assertFalse(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.WRITE_SCHEMA, new PartialPath("root.**")));
    // reuqire root.d1.d2 with write_schema, return true
    Assert.assertTrue(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.WRITE_SCHEMA, new PartialPath("root.d1.d1.**")));

    // require root.d1.d2 with read_schema, return false
    Assert.assertFalse(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.READ_SCHEMA, new PartialPath("root.d1.d2")));

    Assert.assertTrue(
        authorityFetcher.checkUserPrivilegeGrantOpt("user1", PrivilegeType.ALTER, "database"));
    Assert.assertFalse(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.SELECT, "database", "table"));
    // role test
    Assert.assertTrue(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.READ_DATA, new PartialPath("root.t.**")));

    Assert.assertTrue(
        authorityFetcher.checkUserPrivilegeGrantOpt(
            "user1", PrivilegeType.READ_DATA, new PartialPath("root.t.t1")));
    Assert.assertFalse(
        authorityFetcher.checkUserPrivilegeGrantOpt("user1", PrivilegeType.USE_TRIGGER));
    Assert.assertTrue(authorityFetcher.checkUserPrivilegeGrantOpt("user1", PrivilegeType.USE_CQ));
  }
}
