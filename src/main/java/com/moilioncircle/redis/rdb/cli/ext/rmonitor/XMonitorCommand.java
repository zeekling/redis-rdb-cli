/*
 * Copyright 2016-2017 Leon Chen
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

package com.moilioncircle.redis.rdb.cli.ext.rmonitor;


import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.ALL;
import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.CONFIG;
import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.GET;
import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.INFO;
import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.LEN;
import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.MAXCLIENTS;
import static com.moilioncircle.redis.rdb.cli.ext.datatype.CommandConstants.SLOWLOG;
import static com.moilioncircle.redis.rdb.cli.ext.rmonitor.support.XStandaloneRedisInfo.EMPTY;

import java.io.Closeable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.moilioncircle.redis.rdb.cli.conf.Configure;
import com.moilioncircle.redis.rdb.cli.ext.rmonitor.support.XSlowLog;
import com.moilioncircle.redis.rdb.cli.ext.rmonitor.support.XStandaloneRedisInfo;
import com.moilioncircle.redis.rdb.cli.monitor.Monitor;
import com.moilioncircle.redis.rdb.cli.monitor.MonitorFactory;
import com.moilioncircle.redis.rdb.cli.monitor.MonitorManager;
import com.moilioncircle.redis.rdb.cli.net.impl.XEndpoint;
import com.moilioncircle.redis.rdb.cli.net.protocol.RedisObject;
import com.moilioncircle.redis.replicator.Configuration;
import com.moilioncircle.redis.replicator.RedisURI;

import redis.clients.jedis.HostAndPort;

/**
 * @author Baoyi Chen
 */
@SuppressWarnings("unchecked")
public class XMonitorCommand implements Runnable, Closeable {
	
	private static final Monitor monitor = MonitorFactory.getMonitor("monitor");
	
	private final String host;
	private final int port;
	private String name;
	private String hostAndPort;
	private Configure configure;
	private MonitorManager manager;
	private volatile XEndpoint endpoint;
	private final Configuration configuration;
	private XStandaloneRedisInfo prev = EMPTY;
	
	public XMonitorCommand(RedisURI uri, String name, Configure configure) {
		this.name = name;
		this.configure = configure;
		this.manager = new MonitorManager(configure);
		this.manager.open();
		this.host = uri.getHost();
		this.port = uri.getPort();
		this.configuration = configure.merge(uri, true);
		this.hostAndPort = new HostAndPort(host, port).toString();
		this.endpoint = new XEndpoint(this.host, this.port, 0, -1, false, this.configuration);
	}
	
	@Override
	public void run() {
		try {
			endpoint.batch(true, INFO, ALL);
			endpoint.batch(true, CONFIG, GET, MAXCLIENTS);
			endpoint.batch(true, SLOWLOG, LEN);
			endpoint.batch(true, SLOWLOG, GET, "128".getBytes());
			List<RedisObject> list = endpoint.sync();
			
			String info = list.get(0).getString();
			String maxclients = list.get(1).getArray()[1].getString();
			Long len = list.get(2).getNumber();
			RedisObject[] binaryLogs = list.get(3).getArray();
			
			XStandaloneRedisInfo next = XStandaloneRedisInfo.valueOf(info, maxclients, len, binaryLogs, hostAndPort);
			next = XStandaloneRedisInfo.diff(prev, next);
			
			// server
			long now = System.currentTimeMillis();
			setLong("monitor", hostAndPort, name, now);
			setLong("uptime_in_seconds", hostAndPort, name, next.getUptimeInSeconds());
			setString("redis_version", hostAndPort, name, next.getRedisVersion());
			setString("role", hostAndPort, name, next.getRole());
			
			// clients
			setLong("connected_clients", hostAndPort, name, next.getConnectedClients());
			setLong("blocked_clients", hostAndPort, name, next.getBlockedClients());
			setLong("tracking_clients", hostAndPort, name, next.getTrackingClients());
			setLong("maxclients", hostAndPort, name, next.getMaxclients());
			
			// memory
			setLong("maxmemory", hostAndPort, name, next.getMaxmemory());
			setLong("used_memory", hostAndPort, name, next.getUsedMemory());
			setLong("used_memory_rss", hostAndPort, name, next.getUsedMemoryRss());
			setLong("used_memory_peak", hostAndPort, name, next.getUsedMemoryPeak());
			setLong("used_memory_dataset", hostAndPort, name, next.getUsedMemoryDataset());
			setLong("used_memory_lua", hostAndPort, name, next.getUsedMemoryLua());
			setLong("used_memory_functions", hostAndPort, name, next.getUsedMemoryFunctions());
			setLong("used_memory_scripts", hostAndPort, name, next.getUsedMemoryScripts());
			setLong("total_system_memory", hostAndPort, name, next.getTotalSystemMemory()); // ?
			setDouble("mem_fragmentation_ratio", hostAndPort, name, next.getMemFragmentationRatio());
			setLong("mem_fragmentation_bytes", hostAndPort, name, next.getMemFragmentationBytes());

			// command
			setLong("total_connections_received", hostAndPort, name, next.getTotalConnectionsReceived());
			setLong("total_commands_processed", hostAndPort, name, next.getTotalCommandsProcessed());
			
			setLong("total_reads_processed", hostAndPort, name, next.getTotalReadsProcessed());
			setLong("total_writes_processed", hostAndPort, name, next.getTotalWritesProcessed());
			setLong("total_error_replies", hostAndPort, name, next.getTotalErrorReplies());
			
			Long hits = next.getKeyspaceHits();
			Long misses = next.getKeyspaceMisses();
			if (hits != null && misses != null) {
				monitor.set("keyspace_hit_rate", hostAndPort, name, hits * 1d / (hits + misses));
			}
			
			// ops
			setLong("total_net_input_bytes", hostAndPort, name, next.getTotalNetInputBytes());
			setLong("total_net_output_bytes", hostAndPort, name, next.getTotalNetOutputBytes());
			setDouble("evicted_keys_per_sec", hostAndPort, name, next.getEvictedKeysPerSec());
			setDouble("instantaneous_ops_per_sec", hostAndPort, name, next.getInstantaneousOpsPerSec());
			setDouble("instantaneous_write_ops_per_sec", hostAndPort, name, next.getInstantaneousWriteOpsPerSec());
			setDouble("instantaneous_read_ops_per_sec", hostAndPort, name, next.getInstantaneousReadOpsPerSec());
			setDouble("instantaneous_other_ops_per_sec", hostAndPort, name, next.getInstantaneousOtherOpsPerSec());
			setDouble("instantaneous_sync_write_ops_per_sec", hostAndPort, name, next.getInstantaneousSyncWriteOpsPerSec());
			setDouble("instantaneous_input_kbps", hostAndPort, name, next.getInstantaneousInputKbps());
			setDouble("instantaneous_output_kbps", hostAndPort, name, next.getInstantaneousOutputKbps());
			
			// cpu
			setDouble("used_cpu_sys", hostAndPort, name, next.getUsedCpuSys());
			setDouble("used_cpu_user", hostAndPort, name, next.getUsedCpuUser());
			setDouble("used_cpu_sys_children", hostAndPort, name, next.getUsedCpuSysChildren());
			setDouble("used_cpu_user_children", hostAndPort, name, next.getUsedCpuUserChildren());
			
			// db
			for (Map.Entry<String, Long> entry : next.getDbInfo().entrySet()) {
				monitor.set("dbnum", hostAndPort, name, entry.getKey(), entry.getValue());
			}
			for (Map.Entry<String, Long> entry : next.getDbExpireInfo().entrySet()) {
				monitor.set("dbexp", hostAndPort, name, entry.getKey(), entry.getValue());
			}
			
			// diff
			setLong("expired_keys", hostAndPort, name, next.getExpiredKeys());
			setLong("evicted_keys", hostAndPort, name, next.getEvictedKeys());
			
			// slow log
			setLong("total_slow_log", hostAndPort, name, next.getTotalSlowLog());
			
			List<XSlowLog> slowLogs = next.getDiffSlowLogs();
			for (XSlowLog slowLog : slowLogs) {
				String[] properties = new String[5];
				properties[0] = hostAndPort;
				properties[1] = name;
				properties[2] = String.valueOf(slowLog.getId());
				properties[3] = slowLog.getTimestamp();
				properties[4] = slowLog.getCommand();
				properties[5] = slowLog.getClientName();
				properties[6] = slowLog.getHostAndPort();
				monitor.set("slow_log", properties, slowLog.getExecutionTime());
			}
			
			if (next.getDiffTotalSlowLog() > 0) {
				monitor.set("slow_log_latency", hostAndPort, name, (next.getDiffTotalSlowLogExecutionTime() / (next.getDiffTotalSlowLog() * 1d)));
			} else {
				monitor.set("slow_log_latency", hostAndPort, name, 0d);
			}
			
			prev = next;
			delay(15, TimeUnit.SECONDS);
		} catch (Throwable e) {
			this.endpoint = XEndpoint.valueOfQuietly(this.endpoint, 0);
		}
	}
	
	private void setLong(String field, String hostAndPort, String name, Long value) {
		if (value != null) {
			monitor.set(field, hostAndPort, name, value);
		}
	}
	
	private void setDouble(String field, String hostAndPort, String name, Double value) {
		if (value != null) {
			monitor.set(field, hostAndPort, name, value);
		}
	}
	
	private void setString(String field, String hostAndPort, String name, String value) {
		if (value != null) {
			monitor.set(field, hostAndPort, name, value);
		}
	}
	
	private void delay(long time, TimeUnit unit) {
		try {
			unit.sleep(time);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
	
	@Override
	public void close() {
		XEndpoint.closeQuietly(endpoint);
		MonitorManager.closeQuietly(manager);
	}
}
