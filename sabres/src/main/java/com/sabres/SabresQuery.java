/*
 * Copyright 2015 Tamir Shomer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sabres;

import android.database.Cursor;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;

import bolts.Continuation;
import bolts.Task;

/**
 * The SabresQuery class defines a query that is used to fetch SabresObjects.
 * The most common use case is finding all objects that match a query through the
 * {@link #findInBackground(FindCallback)} method, using a FindCallback. For example,
 * this sample code fetches all objects of class "MyClass".
 * <pre>
 * {@code
 * SabresQuery<MyClass> query = SabresQuery.getQuery(MyClass.class);
 * query.findInBackground(new FindCallback<MyClass>() {
 *     public void done(List<MyClass> objects, SabresException e) {
 *         if (e == null) {
 *             objectsWereRetrievedSuccessfully(objects);
 *         } else {
 *             objectRetrievalFailed();
 *         }
 *    }
 * });
 * }
 * </pre>
 * A SabresQuery can also be used to retrieve a single object whose id is known,
 * through the {@link #getInBackground(long)} method, using a GetCallback.
 * For example, this sample code fetches an object of class "MyClass" for id myId.
 * <pre>
 * {@code
 * SabresQuery<MyClass> query = ParseQuery.getQuery(MyClass.class);
 * query.getInBackground(myId, new GetCallback<MyClass>() {
 *     public void done(MyClass object, SabresException e) {
 *         if (e == null) {
 *             objectWasRetrievedSuccessfully(object);
 *         } else {
 *             objectRetrievalFailed();
 *         }
 *     }
 * });
 * }
 * </pre>
 * A SabresQuery can also be used to count the number of objects that match the query without
 * retrieving all of those objects. For example,
 * this sample code counts the number of objects of the class "MyClass".
 * <pre>
 * {@code
 * SabresQuery<MyClass> query = ParseQuery.getQuery(MyClass.class);
 * query.countInBackground(new CountCallback() {
 *     public void done(int count, SabresException e) {
 *         if (e == null) {
 *             objectsWereCounted(count);
 *         } else {
 *             objectCountFailed();
 *         }
 *     }
 * });
 * }
 * </pre>
 * Using the callback methods is usually preferred because the database operation will not block
 * the calling thread. However, in some cases it may be easier to use the
 * {@link #find()}, {@link #get(long)} or {@link #count()} calls, which do block the calling thread.
 * For example, if your application has already spawned a background task to perform work,
 * that background task could use the blocking calls and avoid the code complexity of callbacks.
 */
public class SabresQuery<T extends SabresObject> {
    private static final String TAG = SabresQuery.class.getSimpleName();
    private final String name;
    private final Class<T> clazz;
    private final List<String> keyIndices = new ArrayList<>();
    private final List<String> includes = new ArrayList<>();
    private final List<OrderBy> orderByList = new ArrayList<>();
    private Where where;

    /**
     * Constructs a query for a SabresObject subclass type.
     * A default query with no further parameters will retrieve all SabresObjects of the
     * provided class.
     *
     * @param clazz The SabresObject subclass type to retrieve.
     */
    public SabresQuery(Class<T> clazz) {
        this.clazz = clazz;
        name = clazz.getSimpleName();
    }

    /**
     * Creates a new query for the given SabresObject subclass type.
     * A default query with no further parameters will retrieve all SabresObjects of the
     * provided class.
     *
     * @param clazz The name of the class to retrieve SabresObjects for.
     */
    public static <T extends SabresObject> SabresQuery<T> getQuery(Class<T> clazz) {
        return new SabresQuery<>(clazz);
    }

    static void createIndices(Sabres sabres, String name, List<String> keys)
        throws SabresException {
        CreateIndexCommand createIndexCommand = new CreateIndexCommand(name, keys).ifNotExists();
        sabres.execSQL(createIndexCommand.toString());
    }

    /**
     *  Sorts the results in ascending order by the given key.
     *  Multiple calls with different keys can be made to this and
     *  {@link #addDescendingOrder(String)} functions for a single query.
     *
     * @param key   The key to order by
     * @return      this, so you can chain this call.
     */
    public SabresQuery<T> addAscendingOrder(String key) {
        orderByList.add(new OrderBy(key, OrderBy.Direction.Ascending));
        return this;
    }

    /**
     *  Sorts the results in descending order by the given key.
     *  Multiple calls with different keys can be made to this and
     *  {@link #addAscendingOrder(String)} functions for a single query.
     *
     * @param key   The key to order by
     * @return      this, so you can chain this call.
     */
    public SabresQuery<T> addDescendingOrder(String key) {
        orderByList.add(new OrderBy(key, OrderBy.Direction.Descending));
        return this;
    }

    /**
     * Constructs a SabresObject whose id is already known by fetching data from the database in
     * a background thread.
     * @param  objectId Object id of the SabresObject to fetch.
     * @return A Task that is resolved when the fetch completes.
     */
    public Task<T> getInBackground(final long objectId) {
        return Task.callInBackground(new Callable<T>() {
            @Override
            public T call() throws Exception {
                return get(objectId);
            }
        });
    }

    /**
     * Constructs a SabresObject whose id is already known by fetching data from the database in
     * a background thread.
     * @param  objectId Object id of the SabresObject to fetch.
     * @param callback  callback.done(object, e) will be called when the fetch completes.
     */
    public void getInBackground(long objectId, final GetCallback<T> callback) {
        getInBackground(objectId).continueWith(new Continuation<T, Object>() {
            @Override
            public Object then(Task<T> task) throws Exception {
                callback.done(task.getResult(), SabresException.construct(task.getError()));
                return null;
            }
        });
    }

    /**
     * Include nested SabresObjects for the provided key.
     *
     * @param key The key that should be included.
     * @return    this, so you can chain this call.
     */
    public SabresQuery<T> include(String key) {
        SabresDescriptor descriptor = Schema.getDescriptor(name, key);
        if (descriptor == null) {
            throw new IllegalArgumentException(String.format("Unrecognized key %s in Object %s",
                key, name));
        }

        if (descriptor.getType().equals(SabresDescriptor.Type.Pointer)) {
            includes.add(key);
        } else {
            Log.w(TAG, String.format("keys of type %s are always included in query results",
                descriptor.getType().toString()));
        }

        return this;
    }

    private String stringifyObject(Object object) {
        if (object instanceof Number) {
            return String.valueOf(object);
        }

        if (object instanceof String) {
            return (String)object;
        }

        if (object instanceof Boolean) {
            return (Boolean)object ? "1" : "0";
        }

        if (object instanceof Date) {
            return String.valueOf(((Date)object).getTime());
        }

        if (object instanceof SabresObject) {
            return String.valueOf(((SabresObject)object).getObjectId());
        }

        throw new IllegalArgumentException(String.format("No rule to stringify Object of class %s",
            object.getClass().getSimpleName()));
    }

    /**
     * Add a constraint to the query that requires a particular key's value to be equal to the
     * provided value.
     *
     * @param key    The key to check.
     * @param value The value that the SabresObject must contain.
     * @return      this, so you can chain this call.
     */
    public SabresQuery<T> whereEqualTo(String key, Object value) {
        keyIndices.add(key);
        addWhere(Where.equalTo(key, stringifyObject(value)));
        return this;
    }

    private void addWhere(Where where) {
        if (this.where == null) {
            this.where = where;
        } else {
            this.where = this.where.and(where);
        }
    }

    private void checkTableExists(Sabres sabres) throws SabresException {
        if (!SqliteMaster.tableExists(sabres, name)) {
            throw new SabresException(SabresException.OBJECT_NOT_FOUND,
                String.format("table %s does not exist", name));
        }
    }

    /**
     * Retrieves a list of SabresObjects that satisfy this query from the database in a background
     * thread.
     *
     * @return A Task that will be resolved when the find has completed.
     */
    public Task<List<T>> findInBackground() {
        return Task.callInBackground(new Callable<List<T>>() {
            @Override
            public List<T> call() throws Exception {
                return find();
            }
        });
    }

    /**
     * Retrieves a list of SabresObjects that satisfy this query from the database in a background
     * thread.
     *
     * @param callback  callback.done(objectList, e) is called when the find completes.
     */
    public void findInBackground(final FindCallback<T> callback) {
        findInBackground().continueWith(new Continuation<List<T>, Void>() {
            @Override
            public Void then(Task<List<T>> task) throws Exception {
                callback.done(task.getResult(), SabresException.construct(task.getError()));
                return null;
            }
        }, Task.UI_THREAD_EXECUTOR);
    }

    /**
     * Counts the number of objects that match this query.
     *
     * @return The number of object that match the query.
     * @throws SabresException Throws an exception when the query is invalid.
     */
    public long count() throws SabresException {
        Sabres sabres = Sabres.self();
        sabres.open();
        try {
            return sabres.count(new CountCommand(name).toSql());
        } finally {
            sabres.close();
        }
    }

    /**
     * Counts the number of objects that match this query in a background thread.
     *
     * @param callback callback.done(count, e) will be called when the count completes.
     */
    public void countInBackground(final CountCallback callback) {
        countInBackground().continueWith(new Continuation<Long, Void>() {
            @Override
            public Void then(Task<Long> task) throws Exception {
                callback.done(task.getResult(), SabresException.construct(task.getError()));
                return null;
            }
        }, Task.UI_THREAD_EXECUTOR);
    }

    /**
     * Counts the number of objects that match this query in a background thread.
     *
     * @return A Task that will be resolved when the count has completed.
     */
    public Task<Long> countInBackground() {
        return Task.callInBackground(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                return count();
            }
        });
    }

    /**
     * Retrieves a list of SabresObjects that satisfy this query.
     *
     * @return A list of all SabresObjects obeying the conditions set in this query.
     * @throws SabresException Throws a SabresException if there was an error with the query.
     */
    public List<T> find() throws SabresException {
        Sabres sabres = Sabres.self();
        List<T> objects = new ArrayList<>();
        sabres.open();
        Cursor c = null;
        try {
            if (SqliteMaster.tableExists(sabres, name)) {
                createIndices(sabres, name, keyIndices);
                SelectCommand command = new SelectCommand(name, Schema.getKeys(name));
                for (String include : includes) {
                    SabresDescriptor descriptor = Schema.getDescriptor(name, include);
                    if (descriptor != null &&
                        descriptor.getType().equals(SabresDescriptor.Type.Pointer)) {
                        command.join(descriptor.getName(), include,
                            Schema.getKeys(descriptor.getName()));
                    }
                }

                for (OrderBy orderBy : orderByList) {
                    command.orderBy(orderBy);
                }

                c = sabres.select(command.where(where).toSql());
                for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
                    T object = createObjectInstance();
                    object.populate(sabres, c);
                    for (String include : includes) {
                        object.populateChild(sabres, c, include);
                    }

                    objects.add(object);
                }
            }

            return objects;
        } finally {
            if (c != null) {
                c.close();
            }

            sabres.close();
        }
    }

    private T createObjectInstance() {
        try {
            return clazz.newInstance();
        } catch (Exception e) {
            throw new RuntimeException(String.format("Failed to instantiate class %s",
                clazz.getSimpleName()), e);
        }
    }

    /**
     * Constructs a SabresObject whose id is already known by fetching data from the database.
     *
     * @param objectId
     * @return Object id of the ParseObject to fetch.
     * @throws SabresException  Throws an exception when there is no such object or if there's a
     * database error.
     * @see SabresException#OBJECT_NOT_FOUND
     */
    public T get(long objectId) throws SabresException {
        Sabres sabres = Sabres.self();
        sabres.open();
        try {
            checkTableExists(sabres);
            T instance = SabresObject.createWithoutData(name, objectId);
            instance.fetch(sabres);
            return instance;
        } finally {
            sabres.close();
        }
    }
}
