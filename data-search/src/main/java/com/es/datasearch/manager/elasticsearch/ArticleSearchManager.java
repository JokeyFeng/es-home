package com.es.datasearch.manager.elasticsearch;

import com.alibaba.fastjson.JSON;
import com.es.datasearch.param.QueryArticleSearchVO;
import com.es.datasearch.util.ConvertArray;
import com.es.datasearch.util.ConvertArticleDTO;
import com.es.stone.enums.EsStatus;
import com.es.stone.manager.ElasticSearchInitClientManager;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.geo.GeoPoint;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.Operator;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.GeoDistanceSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ArticleSearchManager {

    private static final Logger logger = LoggerFactory
            .getLogger(ArticleSearchManager.class);

    private final static String articleIndex = "db_search.tb_article";

    private final static String generalType = "doc";

    @Autowired
    private ElasticSearchInitClientManager elasticSearchInitClientManager;

    /**
     * article列表信息列表查询
     *
     * @param queryArticleSearchVO
     * @return
     */
    public String queryArticleList(QueryArticleSearchVO queryArticleSearchVO) {
        logger.info("ArticleSearchManager.queryArticleList.queryArticleSearchVO:{}", JSON.toJSONString(queryArticleSearchVO));
        // 存放最终结果信息
        Map result = new HashMap();
        //存放文章列表信息
        List<Map> articleList = new ArrayList<>();
        // 限制最多查询50条
        if (queryArticleSearchVO.pageSize < 1 || queryArticleSearchVO.pageSize > 50) {
            result.put("recordSize", 0);
            result.put("esStatus", EsStatus.PARAM_ERROR);
            return JSON.toJSONString(result);
        }
        RestHighLevelClient client = elasticSearchInitClientManager.getElasticClient();
        SearchRequest searchRequest = new SearchRequest(articleIndex);
        searchRequest.types(generalType);

        //组装查询条件
        SearchSourceBuilder searchSourceBuilder = builderArticleSquareCondition(queryArticleSearchVO);

        searchRequest.source(searchSourceBuilder);
        //查询条件组装完成-------------------------------------------------------------------------------------------------
        try {
            //开始查询
            SearchResponse rsp = client.search(searchRequest);
            SearchHits hits = rsp.getHits();
            result.put("recordSize", hits.getTotalHits());
            for (SearchHit hit : hits) {
                Map recordMap = hit.getSourceAsMap();
                ConvertArticleDTO.convertDate(recordMap);
                articleList.add(recordMap);
            }
        } catch (Exception e) {
            logger.error("queryArticleList search from elasticsearch error!" + searchRequest.toString(), e);
        }
        result.put("pageNo", queryArticleSearchVO.pageNo);
        result.put("pageSize", queryArticleSearchVO.pageSize);
        result.put("articleList", articleList);
        result.put("esStatus", EsStatus.SUCESS);
        return JSON.toJSONString(result);
    }

    /**
     * 组装文章查询逻辑
     *
     * @param queryArticleSearchVO
     * @return
     */
    protected static SearchSourceBuilder builderArticleSquareCondition(QueryArticleSearchVO queryArticleSearchVO) {
        //组装查询条件-----------------------------------------------------------------------------
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        if (queryArticleSearchVO.pageNo > 0) {
            int from = (queryArticleSearchVO.pageNo - 1) * queryArticleSearchVO.pageSize;
            int size = queryArticleSearchVO.pageSize;
            //设置分页
            searchSourceBuilder.from(from);
            searchSourceBuilder.size(size);
        }

        BoolQueryBuilder bq = QueryBuilders.boolQuery();

        if (!StringUtils.isEmpty(queryArticleSearchVO.keyWords)) {
            //设置是否关键字查询条件，并增加了ik分词器，字段的分词器也需要在建索引时进行设置
            bq.must(QueryBuilders.multiMatchQuery(queryArticleSearchVO.keyWords, "title", "content").analyzer("ik_smart").operator(Operator.AND).fuzziness(Fuzziness.AUTO));
        }
        if (queryArticleSearchVO.id > 0) {
            //设置是否主键id查询条件
            bq.must(QueryBuilders.termQuery("id", queryArticleSearchVO.id));
        }
        if (!StringUtils.isEmpty(queryArticleSearchVO.title)) {
            //设置是否文章标题查询条件，并增加了ik分词器，字段的分词器也需要在建索引时进行设置
            bq.must(QueryBuilders.matchQuery("title", queryArticleSearchVO.title).analyzer("ik_smart").operator(Operator.AND).fuzziness(Fuzziness.AUTO));
        }
        if (!StringUtils.isEmpty(queryArticleSearchVO.content)) {
            //设置是否文章内容查询条件，并增加了ik分词器，字段的分词器也需要在建索引时进行设置
            bq.must(QueryBuilders.matchQuery("content", queryArticleSearchVO.content).analyzer("ik_smart").operator(Operator.AND).fuzziness(Fuzziness.AUTO));
        }
        if (!StringUtils.isEmpty(queryArticleSearchVO.state)) {
            //设置是否文章状态查询条件，类似SQL中的 select * from tb_article where state in (1,2);
            //queryArticleSearchVO.state 需要转成int数组
            bq.must(QueryBuilders.termsQuery("state", ConvertArray.convertArray(queryArticleSearchVO.state)));
        }
        if (queryArticleSearchVO.createStartTime != null) {
            bq.filter(QueryBuilders.rangeQuery("create_time").format("yyyy-MM-dd").gte(queryArticleSearchVO.createStartTime).timeZone("Asia/Shanghai"));
        }
        if (queryArticleSearchVO.createEndTime != null) {
            bq.filter(QueryBuilders.rangeQuery("create_time").format("yyyy-MM-dd").lte(queryArticleSearchVO.createEndTime).timeZone("Asia/Shanghai"));
        }
        searchSourceBuilder.query(bq);

        if (queryArticleSearchVO.sortType == 1) {
            //当排序规则传入为1时，按照距离正序排序
            searchSourceBuilder.sort(new GeoDistanceSortBuilder("location",
                    GeoPoint.parseFromLatLon(queryArticleSearchVO.latitude + "," + queryArticleSearchVO.longitude))
                    .order(SortOrder.ASC));
        } else if (queryArticleSearchVO.sortType == 0) {
            //当排序规则传入为0时，按照时间倒序排序
            //按文章创建时间倒序排列。
            searchSourceBuilder.sort(new FieldSortBuilder("create_time").order(SortOrder.DESC));
        }
        return searchSourceBuilder;
    }

}
