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
package org.apache.iotdb.commons.auth.entity;

import org.apache.iotdb.commons.snapshot.SnapshotProcessor;

import java.io.IOException;
import java.util.List;

/** This interface manages the serialization/deserialization of the entry objects. */
public interface IEntryAccessor extends SnapshotProcessor {

  /**
   * Deserialize an entry from lower storage.
   *
   * @param entryName The name of the user/role to be deserialized.
   * @return The user object or null if no such entry.
   * @throws IOException if an exception is raised when interacting with the lower storage.
   */
  Role loadEntry(String entryName) throws IOException;

  /**
   * Serialize the entry object to lower storage.
   *
   * @param entry The user/role object that is to be saved.
   * @throws IOException if an exception is raised when interacting with the lower storage.
   */
  void saveEntry(Role entry) throws IOException;

  /**
   * Delete a user's from lower storage.
   *
   * @param entryName The name of the user/role to be deleted.
   * @return True if the user/role is successfully deleted, false if the user does not exist.
   * @throws IOException if an exception is raised when interacting with the lower storage.
   */
  boolean deleteEntry(String entryName) throws IOException;

  /**
   * List all users existing in the database.
   *
   * @return A list that contains names of all users.
   */
  List<String> listAllEntries();

  /** Delete entries' folders. */
  void cleanEntryFolder();

  /** Re-initialize this object. */
  void reset();
}
