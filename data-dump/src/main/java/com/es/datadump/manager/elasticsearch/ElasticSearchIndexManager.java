package com.es.datadump.manager.elasticsearch;

import com.es.stone.manager.ElasticSearchDumpManager;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.common.settings.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

/**
 * @author yiheni
 */
public class ElasticSearchIndexManager {

    private final static Logger logger = LoggerFactory.getLogger(ElasticSearchIndexManager.class);

    @Autowired
    private ElasticSearchDumpManager elasticSearchDumpManager;

    /**
     * db_search.tb_article创建逻辑
     *
     * @param index
     * @return
     */
    protected CreateIndexRequest convertTbArticle(String index) {
        CreateIndexRequest createIndexRequest = new CreateIndexRequest();
        //同步模块创建的索引，默认版本都为v1，同步时，使用别名进行同步
        createIndexRequest.index(index + "_v1");
        //创建别名
        createIndexRequest.alias(new Alias(index));
        createIndexRequest.settings(Settings.builder().put("number_of_shards", 7).put("max_result_window", 100000));
        createIndexRequest.mapping("doc",
                "title", "type=text,fielddata=true,analyzer=ik_smart",//设置 title 分词
                "content", "type=text,fielddata=true,analyzer=ik_smart", //设置 content 分词
                "location", "type=geo_point");//设置 location 经纬度字段，用于距离排序
        return createIndexRequest;
    }

    /**
     * 检查索引情况并根据情况创建索引
     *
     * @param index
     * @return
     */
    public boolean checkIndex(String... index) {
        boolean flag = true;
        if (index != null) {
            List<String> indexList = Arrays.asList(index);
            try {
                //需特殊处理的索引列表
                for (String idx : indexList) {
                    //先判断索引是否存在，若不存在，则继续判断是什么索引
                    if (!elasticSearchDumpManager.isExistsIndex(idx)) {
                        CreateIndexRequest createIndexRequest = null;
                        //针对特殊索引配置初始化逻辑
                        if ("db_search.tb_article".equals(idx)) {
                            //设置索引初始化mapper
                            createIndexRequest = convertTbArticle("db_search.tb_article");
                        }
                        //设置完成mapper后进行判断
                        //mapper非空则创建索引
                        if (createIndexRequest != null) {
                            //根据mapper创建索引
                            flag = elasticSearchDumpManager.createIndex(createIndexRequest);
                            //若创建失败，则程序终止
                            if (!flag) {
                                logger.error("索引" + idx + "创建失败！");
                                return flag;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                //出现异常false
                flag = false;
                logger.error("索引检查失败，程序退出！", e);
            }
        }
        return flag;
    }

}
