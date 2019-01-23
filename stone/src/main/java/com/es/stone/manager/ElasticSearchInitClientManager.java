package com.es.stone.manager;

import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author yiheni
 */
@Component
@Configuration
public class ElasticSearchInitClientManager {
    private final static Logger logger = LoggerFactory.getLogger(ElasticSearchInitClientManager.class);
    private final static int POOL_SIZE = 50;
    private static volatile int idleConnect;
    private static BlockingQueue<RestHighLevelClient> pool = null;

    @Value("${es.client.servers}")
    private String servers;

    private RestHighLevelClient client = null;

    public final RestHighLevelClient getElasticClient() {
        if (client == null) {
            HttpHost[] httpHosts = this.serverToHttpHost(servers);
            if (httpHosts != null) {
                try {
                    client = new RestHighLevelClient(RestClient.builder(httpHosts));
                } catch (Exception e) {
                    logger.error("创建es客户端对象异常:", e);
                }
            }
        }
        return client;
    }

    protected HttpHost[] serverToHttpHost(String servers) {
        String[] arrServer = servers.split(",");
        List<HttpHost> hostList = new ArrayList<>();
        HttpHost[] hostArr;
        if (arrServer.length < 1) {
            logger.error("ES服务器配置不正确，请检查！");
            return null;
        }
        for (String server : arrServer) {
            String[] ipAndPort = server.split(":");
            if (ipAndPort.length == 2) {
                try {
                    hostList.add(new HttpHost(ipAndPort[0], Integer.valueOf(ipAndPort[1]), "http"));
                } catch (Exception e) {
                    logger.error("ES服务器地址配置不正确!", e);
                    return null;
                }
            } else {
                logger.error("ES服务器配置的ip与port不正确。错误值:" + Arrays.toString(ipAndPort));
                return null;
            }
        }
        if (!hostList.isEmpty()) {
            hostArr = new HttpHost[hostList.size()];
            return hostList.toArray(hostArr);
        }
        return null;
    }

    /**
     * 初始化ES连接池
     * 连接池有上限，若连接池满，则阻塞等待占用的线程释放ES客户端连接。
     * 一旦连接被释放，池中存在空闲客户端，即可继续被消费。
     */
    public synchronized void initClientPool() {
        if (pool != null) {
            return;
        }
        HttpHost[] httpHosts = serverToHttpHost(servers);
        pool = new LinkedBlockingQueue<>(POOL_SIZE);
        for (int i = 0; i < POOL_SIZE; i++) {
            try {
                pool.put(new RestHighLevelClient(RestClient.builder(httpHosts)));
            } catch (InterruptedException e) {
                logger.error("同步服务初始化ES连接池失败，退出！", e);
                System.exit(0);
            }
        }
    }

    /**
     * 从线程池中获取ES客户端
     *
     * @return
     */
    public RestHighLevelClient getClientFromPool() {
        if (pool == null) {
            initClientPool();
        }
        try {
            return ElasticSearchInitClientManager.pool.take();
        } catch (InterruptedException e) {
            logger.error("从线程池中获取客户端异常！", e);
        } finally {
            ElasticSearchInitClientManager.idleConnect = ElasticSearchInitClientManager.pool.size();
        }
        return null;
    }

    public void disConnect(RestHighLevelClient client) {
        try {
            ElasticSearchInitClientManager.pool.put(client);
            ElasticSearchInitClientManager.idleConnect = ElasticSearchInitClientManager.pool.size();
        } catch (InterruptedException e) {
            logger.error("同步服务断开连接异常！", e);
        }
    }

    public String getServers() {
        return servers;
    }

    public void setServers(String servers) {
        this.servers = servers;
    }

    public static int getIdleconnect() {
        return idleConnect;
    }

}
