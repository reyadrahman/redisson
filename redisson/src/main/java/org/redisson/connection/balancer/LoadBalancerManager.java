/**
 * Copyright 2016 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson.connection.balancer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;

import org.redisson.api.NodeType;
import org.redisson.api.RFuture;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.client.protocol.RedisCommand;
import org.redisson.config.MasterSlaveServersConfig;
import org.redisson.config.ReadMode;
import org.redisson.connection.ClientConnectionsEntry;
import org.redisson.connection.ClientConnectionsEntry.FreezeReason;
import org.redisson.connection.ConnectionManager;
import org.redisson.connection.CountableListener;
import org.redisson.connection.MasterSlaveEntry;
import org.redisson.connection.pool.PubSubConnectionPool;
import org.redisson.connection.pool.SlaveConnectionPool;
import org.redisson.misc.RPromise;
import org.redisson.misc.RedissonPromise;
import org.redisson.misc.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.internal.PlatformDependent;

/**
 * 
 * @author Nikita Koksharov
 *
 */
public class LoadBalancerManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ConnectionManager connectionManager;
    private final PubSubConnectionPool pubSubConnectionPool;
    private final SlaveConnectionPool slaveConnectionPool;
    
    private final Map<RedisClient, ClientConnectionsEntry> client2Entry = PlatformDependent.newConcurrentHashMap();

    public LoadBalancerManager(MasterSlaveServersConfig config, ConnectionManager connectionManager, MasterSlaveEntry entry) {
        this.connectionManager = connectionManager;
        slaveConnectionPool = new SlaveConnectionPool(config, connectionManager, entry);
        pubSubConnectionPool = new PubSubConnectionPool(config, connectionManager, entry);
    }

    public void changeType(RedisClient redisClient, NodeType nodeType) {
        ClientConnectionsEntry entry = getEntry(redisClient);
        changeType(nodeType, entry);
    }

    protected void changeType(NodeType nodeType, ClientConnectionsEntry entry) {
        if (entry != null) {
            if (connectionManager.isClusterMode()) {
                entry.getClient().getConfig().setReadOnly(nodeType == NodeType.SLAVE && connectionManager.getConfig().getReadMode() != ReadMode.MASTER);
            }
            entry.setNodeType(nodeType);
        }
    }
    
    public void changeType(URI address, NodeType nodeType) {
        ClientConnectionsEntry entry = getEntry(address);
        changeType(nodeType, entry);
    }
    
    public RFuture<Void> add(final ClientConnectionsEntry entry) {
        RPromise<Void> result = new RedissonPromise<Void>();
        
        CountableListener<Void> listener = new CountableListener<Void>(result, null) {
            public void operationComplete(io.netty.util.concurrent.Future<Object> future) throws Exception {
                super.operationComplete(future);
                if (this.result.isSuccess()) {
                    client2Entry.put(entry.getClient(), entry);
                }
            };
        };

        RFuture<Void> slaveFuture = slaveConnectionPool.add(entry);
        listener.incCounter();
        slaveFuture.addListener(listener);
        
        RFuture<Void> pubSubFuture = pubSubConnectionPool.add(entry);
        listener.incCounter();
        pubSubFuture.addListener(listener);
        return result;
    }

    public int getAvailableClients() {
        int count = 0;
        for (ClientConnectionsEntry connectionEntry : client2Entry.values()) {
            if (!connectionEntry.isFreezed()) {
                count++;
            }
        }
        return count;
    }

    public boolean unfreeze(URI address, FreezeReason freezeReason) {
        ClientConnectionsEntry entry = getEntry(address);
        if (entry == null) {
            throw new IllegalStateException("Can't find " + address + " in slaves!");
        }

        return unfreeze(entry, freezeReason);
    }
    
    public boolean unfreeze(InetSocketAddress address, FreezeReason freezeReason) {
        ClientConnectionsEntry entry = getEntry(address);
        if (entry == null) {
            throw new IllegalStateException("Can't find " + address + " in slaves!");
        }

        return unfreeze(entry, freezeReason);
    }

    
    public boolean unfreeze(ClientConnectionsEntry entry, FreezeReason freezeReason) {
        synchronized (entry) {
            if (!entry.isFreezed()) {
                return false;
            }
            if ((freezeReason == FreezeReason.RECONNECT
                    && entry.getFreezeReason() == FreezeReason.RECONNECT)
                        || freezeReason != FreezeReason.RECONNECT) {
                entry.resetFailedAttempts();
                entry.setFreezed(false);
                entry.setFreezeReason(null);
                return true;
            }
        }
        return false;
    }

    public ClientConnectionsEntry freeze(URI address, FreezeReason freezeReason) {
        ClientConnectionsEntry connectionEntry = getEntry(address);
        return freeze(connectionEntry, freezeReason);
    }
    
    public ClientConnectionsEntry freeze(InetSocketAddress address, FreezeReason freezeReason) {
        ClientConnectionsEntry connectionEntry = getEntry(address);
        return freeze(connectionEntry, freezeReason);
    }

    
    public ClientConnectionsEntry freeze(RedisClient redisClient, FreezeReason freezeReason) {
        ClientConnectionsEntry connectionEntry = getEntry(redisClient);
        return freeze(connectionEntry, freezeReason);
    }

    public ClientConnectionsEntry freeze(ClientConnectionsEntry connectionEntry, FreezeReason freezeReason) {
        if (connectionEntry == null) {
            return null;
        }

        synchronized (connectionEntry) {
            // only RECONNECT freeze reason could be replaced
            if (connectionEntry.getFreezeReason() == null
                    || connectionEntry.getFreezeReason() == FreezeReason.RECONNECT) {
                connectionEntry.setFreezed(true);
                connectionEntry.setFreezeReason(freezeReason);
                return connectionEntry;
            }
            if (connectionEntry.isFreezed()) {
                return null;
            }
        }

        return connectionEntry;
    }

    public RFuture<RedisPubSubConnection> nextPubSubConnection() {
        return pubSubConnectionPool.get();
    }

    public boolean contains(InetSocketAddress addr) {
        return getEntry(addr) != null;
    }
    
    public boolean contains(URI addr) {
        return getEntry(addr) != null;
    }
    
    public boolean contains(RedisClient redisClient) {
        return getEntry(redisClient) != null;
    }

    protected ClientConnectionsEntry getEntry(URI addr) {
        for (ClientConnectionsEntry entry : client2Entry.values()) {
            InetSocketAddress entryAddr = entry.getClient().getAddr();
            if (URIBuilder.compare(entryAddr, addr)) {
                return entry;
            }
        }
        return null;
    }
    
    protected ClientConnectionsEntry getEntry(InetSocketAddress address) {
        for (ClientConnectionsEntry entry : client2Entry.values()) {
            InetSocketAddress addr = entry.getClient().getAddr();
            if (addr.getAddress().equals(address.getAddress()) && addr.getPort() == address.getPort()) {
                return entry;
            }
        }
        return null;
    }

    
    protected ClientConnectionsEntry getEntry(RedisClient redisClient) {
        return client2Entry.get(redisClient);
    }

    protected String convert(InetSocketAddress addr) {
        return addr.getAddress().getHostAddress() + ":" + addr.getPort();
    }
    
    public RFuture<RedisConnection> getConnection(RedisCommand<?> command, URI addr) {
        ClientConnectionsEntry entry = getEntry(addr);
        if (entry != null) {
            return slaveConnectionPool.get(command, entry);
        }
        RedisConnectionException exception = new RedisConnectionException("Can't find entry for " + addr);
        return connectionManager.newFailedFuture(exception);
    }

    public RFuture<RedisConnection> nextConnection(RedisCommand<?> command) {
        return slaveConnectionPool.get(command);
    }

    public void returnPubSubConnection(RedisPubSubConnection connection) {
        ClientConnectionsEntry entry = getEntry(connection.getRedisClient());
        pubSubConnectionPool.returnConnection(entry, connection);
    }

    public void returnConnection(RedisConnection connection) {
        ClientConnectionsEntry entry = getEntry(connection.getRedisClient());
        slaveConnectionPool.returnConnection(entry, connection);
    }

    public void shutdown() {
        for (ClientConnectionsEntry entry : client2Entry.values()) {
            entry.getClient().shutdown();
        }
    }

    public void shutdownAsync() {
        for (ClientConnectionsEntry entry : client2Entry.values()) {
            connectionManager.shutdownAsync(entry.getClient());
        }
    }

}
