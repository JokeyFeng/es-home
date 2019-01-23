package com.es.datadump.run;

import com.alibaba.otter.canal.client.CanalConnector;
import com.es.datadump.manager.canal.CanalCoreManager;
import com.es.datadump.manager.canal.CanalInitClientManager;
import com.es.datadump.manager.ServiceImportManager;
import com.es.stone.manager.ElasticSearchDumpManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 此处只能监听到ContextClosedEvent
 * 因此，考虑使用CommandLineRunner实现启动后自动执行
 *
 * @author Administrator
 */
@Component
//@Order(3)
public class CanalClientRunner implements CommandLineRunner {

    private final static Logger logger = LoggerFactory.getLogger(CanalClientRunner.class);

    @Autowired
    private CanalInitClientManager canalInitClientManager;

    @Autowired
    private ElasticSearchDumpManager elasticSearchDumpManager;

    @Autowired
    private ServiceImportManager serviceImportManager;

    @Override
    public void run(String... args) throws Exception {
        logger.info("-------------------------------------canal服务启动----------------------------------------------");
        // 根据ip，直接创建链接，无HA的功能
        CanalConnector canalConnector = canalInitClientManager.getCanalConnector();

        final CanalCoreManager canalManager = new CanalCoreManager(canalInitClientManager.getDestination());
        canalManager.setCanalConnector(canalConnector);
        canalManager.setElasticSearchDumpManager(elasticSearchDumpManager);
        canalManager.setServiceImportManager(serviceImportManager);
        canalManager.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                logger.info("## stop the canal client");
                canalManager.stop();
            } catch (Throwable e) {
                logger.warn("##something wrong happens when stopping canal:", e);
            } finally {
                logger.info("## canal client is down.");
            }
        }));
    }
}
