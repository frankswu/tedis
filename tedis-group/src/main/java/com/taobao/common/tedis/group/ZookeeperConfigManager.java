/**
 * (C) 2011-2012 Alibaba Group Holding Limited.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 *
 */
package com.taobao.common.tedis.group;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;

import com.taobao.common.tedis.config.ConfigManager;
import com.taobao.common.tedis.config.HAConfig;
import com.taobao.common.tedis.config.HAConfig.ServerInfo;
import com.taobao.common.tedis.config.HAConfig.ServerProperties;
import com.taobao.common.tedis.config.Router;
import com.taobao.common.tedis.dislock.ZKClient;
import com.taobao.common.tedis.dislock.ZKException;
import com.taobao.common.tedis.util.ZKUtil;

/**
 * ZK实现
 *
 * @author juxin.zj E-mail:juxin.zj@taobao.com
 * @since 2012-4-19 上午11:43:59
 * @version 1.0
 */
public class ZookeeperConfigManager implements ConfigManager {

    static Log logger = LogFactory.getLog(ZookeeperConfigManager.class);
    volatile Router router;
    private ZKClient zkClient;
    private String zkAddress;
    private int zkTimeout = 500000;
    int timeout = 3000;
    // 保存所有的配置
    public volatile HAConfig haConfig;
    Executor e = Executors.newSingleThreadExecutor();
    String appName;
    String version;

    public ZookeeperConfigManager(String appName, String version) {
        this.appName = appName;
        this.version = version;
    }

    public void setZkAddress(String zkAddress) {
        this.zkAddress = zkAddress;
    }

    public void setZkTimeout(int zkTimeout) {
        this.zkTimeout = zkTimeout;
    }

    public void init() throws Exception {
        zkClient = new ZKClient(zkAddress, zkTimeout);
        zkClient.init();
        String path = ZKUtil.contact("tedis-config", appName);
        path = ZKUtil.contact(path, version);
        // ip:port:r10,ip:port:r10
        String configString = new String(zkClient.getData(ZKUtil.normalize(path), new ManagerWatcher()));
        this.haConfig = parseConfig(configString);
        if (this.haConfig.password != null) {
            for (ServerProperties sp : haConfig.groups) {
                sp.password = this.haConfig.password;
            }
        }
        if (haConfig.ms) {
            router = new MSRandomRouter(haConfig.groups, haConfig.failover);
        } else {
            router = new RandomRouter(haConfig.groups, haConfig.failover);
        }
    }

    public Router getRouter() {
        return router;
    }

    public void destroy() {
    }

    public static HAConfig parseConfig(String configString) {
        HAConfig config = new HAConfig();
        // timeout
        Pattern p_timeout = Pattern.compile("timeout=([\\s\\S]+?);");
        Matcher m_timeout = p_timeout.matcher(configString);
        if (m_timeout.find()) {
            String s_timeout = m_timeout.group(1);
            logger.info("timeout=" + s_timeout);
            try {
                config.timeout = Integer.parseInt(s_timeout.trim());
            } catch (Exception ex) {
                logger.error("timeout解析错误:", ex);
            }
        }
        // pool_size
        Pattern p_pool_size = Pattern.compile("pool_size=([\\s\\S]+?);");
        Matcher m_pool_size = p_pool_size.matcher(configString);
        if (m_pool_size.find()) {
            String s_pool_size = m_pool_size.group(1);
            logger.info("pool_size=" + s_pool_size);
            try {
                config.pool_size = Integer.parseInt(s_pool_size.trim());
            } catch (Exception ex) {
                logger.error("pool_size解析错误:", ex);
            }
        }

        // password
        Pattern p_password = Pattern.compile("password=([\\s\\S]+?);");
        Matcher m_password = p_password.matcher(configString);
        if (m_password.find()) {
            String s_password = m_password.group(1);
            logger.info("password=" + s_password);
            try {
                config.password = s_password.trim();
            } catch (Exception ex) {
                logger.error("password解析错误:", ex);
            }
        }

        // servers
        Pattern p = Pattern.compile("servers=([\\s\\S]+?);", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(configString);
        if (m.find()) {
            String s_servers = m.group(1);
            logger.info("servers=" + s_servers);
            String[] array = s_servers.trim().split(",");
            List<ServerProperties> servers = new ArrayList<ServerProperties>();
            for (String s : array) {
                ServerProperties sp = new ServerProperties();
                sp.server = new ServerInfo();
                String[] ss = s.split(":");
                if (ss.length >= 2) {
                    sp.server.addr = ss[0];
                    sp.server.port = Integer.parseInt(ss[1]);
                    sp.pool_size = config.pool_size;
                    sp.timeout = config.timeout;
                    sp.password = config.password;
                    if (ss.length == 3) {
                        sp.readWeight = Integer.parseInt(ss[2].toLowerCase().replace("r", "").trim());
                    }
                } else {
                    logger.error("配置错误:" + s);
                }
                servers.add(sp);
            }
            config.groups = servers;
        } else {
            logger.error("servers配置解析不到:" + configString);
        }
        // fail over开关
        Pattern p_failover = Pattern.compile("failover=([\\s\\S]+?);", Pattern.CASE_INSENSITIVE);
        Matcher m_failover = p_failover.matcher(configString);
        if (m_failover.find()) {
            try {
                String s_failover = m.group(1);
                config.failover = Boolean.parseBoolean(s_failover.trim());
            } catch (Throwable t) {
                logger.error("failover开关解析出错", t);
            }
        }
        return config;
    }

    public class ManagerWatcher implements Watcher {

        @Override
        public void process(WatchedEvent event) {
            String string = null;
            try {
                string = new String(zkClient.getData(event.getPath(), this));
            } catch (ZKException e) {
                logger.error("Get config error.", e);
                return;
            }
            logger.warn("配置变更：" + string);
            if (string != null) {
                haConfig = parseConfig(string);
                Router old = router;
                if (haConfig.ms) {
                    router = new MSRandomRouter(haConfig.groups, haConfig.failover);
                } else {
                    router = new RandomRouter(haConfig.groups, haConfig.failover);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                    logger.warn("InterruptedException:", ex);
                }
                old.destroy();
            }
        }
    }

}
