/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.obsidiantoaster.quickstart.service.impl;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.sql.ResultSet;
import io.vertx.rxjava.ext.jdbc.JDBCClient;
import io.vertx.rxjava.ext.sql.SQLRowStream;
import org.obsidiantoaster.quickstart.service.Store;
import rx.Completable;
import rx.Observable;
import rx.Single;

import java.util.NoSuchElementException;

/**
 * The implementation of the store.
 *
 * @author Paulo Lopes
 */
public class JdbcProductStore implements Store {

  private static final String INSERT = "INSERT INTO products (name, stock) VALUES (?, ?::BIGINT)";

  private static final String SELECT_ONE = "SELECT * FROM products WHERE id = ?";

  private static final String SELECT_ALL = "SELECT * FROM products";

  private static final String UPDATE = "UPDATE products SET name = ?, stock = ?::BIGINT WHERE id = ?";

  private static final String DELETE = "DELETE FROM products WHERE id = ?";

  private final JDBCClient db;

  public JdbcProductStore(JDBCClient db) {
    this.db = db;
  }

  @Override
  public Single<JsonObject> create(JsonObject item) {
    if (item == null) {
      return Single.error(new IllegalArgumentException("The item must not be null"));
    }
    if (item.getString("name") == null || item.getString("name").isEmpty()) {
      return Single.error(new IllegalArgumentException("The name must not be null or empty"));
    }
    if (item.getInteger("stock", 0) < 0) {
      return Single.error(new IllegalArgumentException("The stock must greater or equal to 0"));
    }
    if (item.containsKey("id")) {
      return Single.error(new IllegalArgumentException("The created item already contains an 'id'"));
    }

    return db.rxGetConnection()
      .flatMap(conn -> {
        JsonArray params = new JsonArray().add(item.getValue("name")).add(item.getValue("stock", 0));
        return conn
          .rxUpdateWithParams(INSERT, params)
          .map(ur -> item.put("id", ur.getKeys().getLong(0)))
          .doAfterTerminate(conn::close);
      });
  }

  @Override
  public Observable<JsonObject> readAll() {
    return db.rxGetConnection()
      .flatMapObservable(conn ->
        conn
          .rxQueryStream(SELECT_ALL)
          .flatMapObservable(SQLRowStream::toObservable)
          .doAfterTerminate(conn::close))
      .map(array ->
        new JsonObject()
          .put("id", array.getLong(0))
          .put("name", array.getString(1))
          .put("stock", array.getInteger(2))
      );
  }

  @Override
  public Single<JsonObject> read(long id) {
    return db.rxGetConnection()
      .flatMap(conn -> {
        JsonArray param = new JsonArray().add(id);
        return conn
          .rxQueryWithParams(SELECT_ONE, param)
          .map(ResultSet::getRows)
          .flatMap(list -> {
            if (list.isEmpty()) {
              return Single.error(new NoSuchElementException("Item '" + id + "' not found"));
            } else {
              return Single.just(list.get(0));
            }
          })
          .doAfterTerminate(conn::close);
      });
  }

  @Override
  public Completable update(long id, JsonObject item) {
    if (item == null) {
      return Completable.error(new IllegalArgumentException("The item must not be null"));
    }
    if (item.getString("name") == null || item.getString("name").isEmpty()) {
      return Completable.error(new IllegalArgumentException("The name must not be null or empty"));
    }
    if (item.getInteger("stock", 0) < 0) {
      return Completable.error(new IllegalArgumentException("The stock must greater or equal to 0"));
    }
    if (item.containsKey("id") && id != item.getInteger("id")) {
      return Completable.error(new IllegalArgumentException("The 'id' cannot be changed"));
    }

    return db.rxGetConnection()
      .flatMapCompletable(conn -> {
        JsonArray params = new JsonArray().add(item.getValue("name")).add(item.getValue("stock", 0)).add(id);
        return conn.rxUpdateWithParams(UPDATE, params)
          .flatMapCompletable(up -> {
            if (up.getUpdated() == 0) {
              return Completable.error(new NoSuchElementException("Unknown item '" + id + "'"));
            }
            return Completable.complete();
          })
          .doAfterTerminate(conn::close);
      });
  }

  @Override
  public Completable delete(long id) {
    return db.rxGetConnection()
      .flatMapCompletable(conn -> {
        JsonArray params = new JsonArray().add(id);
        return conn.rxUpdateWithParams(DELETE, params)
          .flatMapCompletable(up -> {
            if (up.getUpdated() == 0) {
              return Completable.error(new NoSuchElementException("Unknown item '" + id + "'"));
            }
            return Completable.complete();
          })
          .doAfterTerminate(conn::close);
      });
  }
}
