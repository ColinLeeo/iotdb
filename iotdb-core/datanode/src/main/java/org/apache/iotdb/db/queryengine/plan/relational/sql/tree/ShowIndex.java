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

package org.apache.iotdb.db.queryengine.plan.relational.sql.tree;

import org.apache.iotdb.common.rpc.thrift.TSStatus;
import org.apache.iotdb.commons.auth.entity.PrivilegeType;
import org.apache.iotdb.db.auth.AuthorityChecker;
import org.apache.iotdb.db.exception.sql.SemanticException;
import org.apache.iotdb.rpc.TSStatusCode;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Objects;

import static com.google.common.base.MoreObjects.toStringHelper;
import static java.util.Objects.requireNonNull;

public class ShowIndex extends Statement {
  private final QualifiedName tableName;

  public ShowIndex(QualifiedName tableName) {
    super(null);
    this.tableName = requireNonNull(tableName, "tableName is null");
  }

  public ShowIndex(NodeLocation location, QualifiedName tableName) {
    super(requireNonNull(location, "location is null"));
    this.tableName = requireNonNull(tableName, "tableName is null");
  }

  public QualifiedName getTableName() {
    return tableName;
  }

  @Override
  public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
    return visitor.visitShowIndex(this, context);
  }

  @Override
  public TSStatus checkPermissionBeforeProcess(String userName, String databaseName) {
    if (AuthorityChecker.SUPER_USER.equals(userName)) {
      return new TSStatus(TSStatusCode.SUCCESS_STATUS.getStatusCode());
    }

    if (databaseName == null) {
      throw new SemanticException("unknown database");
    }

    String tableName = this.tableName.toString();
    return AuthorityChecker.getTSStatus(
        AuthorityChecker.checkDBPermision(
                userName, databaseName, PrivilegeType.WRITE_SCHEMA.ordinal())
            || AuthorityChecker.checkDBPermision(
                userName, databaseName, PrivilegeType.READ_SCHEMA.ordinal())
            || AuthorityChecker.checkTBPermission(
                userName, databaseName, tableName, PrivilegeType.READ_SCHEMA.ordinal())
            || AuthorityChecker.checkTBPermission(
                userName, databaseName, tableName, PrivilegeType.WRITE_SCHEMA.ordinal()),
        "NEED ONE PRIVILEGES OF DB OR TABLE");
  }

  @Override
  public List<Node> getChildren() {
    return ImmutableList.of();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ShowIndex showIndex = (ShowIndex) o;
    return Objects.equals(tableName, showIndex.tableName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(tableName);
  }

  @Override
  public String toString() {
    return toStringHelper(this).add("tableName", tableName).toString();
  }
}
